# Plan : conserver le maillage du projecteur sur le GPU

Statut : proposition, sans modification du rendu ni de ses tests.

## Objectif et constats vérifiés

Remplacer la réémission des sommets subdivisés à chaque image par le dessin d'un maillage conservé
dans un `VertexBuffer`. Une caméra qui bouge ne doit pas provoquer de nouvel envoi des sommets.
Les mises à jour réelles de géométrie, d'apparence ou de lumière restent prises en compte.

Dans `ProjectorLighting`, `QuadCache` contient déjà les positions finales dans le monde, les UV,
les couleurs, les normales et les valeurs de lumière. `Subdivider.replay` les réémet une par une
en soustrayant la caméra. Le profil identifie cette réémission comme une cible CPU ; il ne permet
pas de promettre une économie équivalente en FPS ni de séparer toutes les opérations incorporées
dans cette méthode par le compilateur Java.

Le rendu Ars Nouveau 5.13.3 applique une translation de -24 en Y et une échelle de 32 au modèle
du Planarium inversé, puis appelle `BlockRenderDispatcher.renderSingleBlock`. Le mixin actuel
remplace seulement sa lumière et son `MultiBufferSource`.

Le modèle livré `planarium_dimension.json` utilise `render_type: cutout`. Dans le chemin NeoForge
inspecté, il devient `Sheets.cutoutBlockSheet()` : il ne demande pas le tri des faces translucides.
Ce n'est pas une garantie pour un modèle remplacé par un pack de ressources.

## Première version proposée

1. **Délimiter une capture complète du projecteur.** Encadrer l'appel `renderSingleBlock` dans
   `PlanariumProjectorRendererMixin`, plutôt que dessiner depuis chaque `Subdivider.flush`.
   Donner au cache l'identité du monde et la position du projecteur. Conserver initialement le
   parcours des quadrilatères source pour détecter leurs changements ; supprimer la répétition
   des milliers de sommets issus de la subdivision. La fin de capture fixe la liste exacte des
   faces et des couches : les anciennes faces absentes ne doivent pas être redessinées.

2. **Séparer les calculs de lumière du stockage GPU.** Garder la subdivision, la fusion des zones
   uniformes et les éventails qui évitent les fentes. Conserver la relecture toutes les 10 ticks
   et la vérification répartie sur 8 images. Ajouter une révision de contenu, distincte de la
   génération de lumière : une relecture qui retrouve les mêmes valeurs ne salit pas le maillage.

3. **Construire un maillage par projecteur et par couche compatible.** Utiliser le format réel du
   `RenderType`, notamment les UV de lumière et d'overlay, et conserver ses textures et états de rendu.
   Stocker les positions autour d'une origine fixe proche du projecteur, en gardant l'arrondi commun
   à 1/1024 de bloc. Au dessin, appliquer les matrices de la vue et la translation origine-caméra
   une seule fois. Vérifier l'équivalence avec le chemin actuel pour les positions et les normales.

4. **Envoyer les sommets seulement après un changement de contenu.** Marquer la couche concernée
   quand une face change et regrouper les changements jusqu'à la fin de capture. Commencer par un
   envoi complet de cette couche, au plus une fois par image. Cela limite les appels de dessin et
   reste plus simple qu'un buffer par quadrilatère. Mesurer les pointes pendant les changements de
   lumière avant d'envisager des sous-régions ou un découpage en plusieurs buffers.

5. **Dessiner à une étape compatible avec le rendu existant.** Mettre en attente le projecteur
   effectivement rencontré pendant la passe des entités de blocs. Le point de départ proposé est
   `AFTER_BLOCK_ENTITIES`, après le vidage de la couche cutout par Minecraft, avant l'activation
   de la lumière du décor géant dans notre propre traitement de cette étape. Vérifier ce placement
   avec la profondeur, le brouillard, l'overlay et les entités. Utiliser le shader courant de la
   couche, puis restaurer les états et déconnecter le buffer dans un `finally`. Consommer la file
   une seule fois et l'annuler à la fin d'une image, d'un monde ou d'une capture abandonnée.

6. **Préserver un chemin de compatibilité interne.** La première version optimise le cutout standard.
   Une couche translucide, un format ou un contexte de rendu non pris en charge garde le chemin actuel.
   Choisir le chemin pour l'appel complet avant de supprimer son émission normale, afin d'éviter
   un dessin double ou incomplet. Ne pas supposer qu'un simple tri des indices d'un VBO reproduit
   l'ordre d'un lot translucide partagé avec d'autres objets. Aucun nouveau réglage joueur n'est nécessaire.

7. **Gérer explicitement les ressources.** Invalider et libérer les buffers au changement de
   Planarium, de monde, de modèle ou de ressources, à la déconnexion et quand le rendu éclairé
   cesse d'être utilisé. Réutiliser les points de nettoyage de `SceneryReceiver` et `ClientView`.
   Les créations, remplacements et fermetures GPU doivent s'exécuter sur le fil de rendu, même si
   une demande d'invalidation arrive d'ailleurs. Construire un remplacement complet avant de
   remplacer l'ancien buffer ; une erreur doit laisser un chemin de rendu cohérent.

## Compatibilité à vérifier avant de supprimer le chemin normal pour un cas donné

La lumière colorée de Contraption Lights utilise des valeurs marquées dans la lumière packée.
Vérifier leur durée de validité et les traitements appliqués aux sommets lors de leur émission :
le nouveau chemin ne doit ni figer des identifiants temporaires ni contourner une conversion requise.
Un changement de ces données peut imposer une reconstruction même si la lumière paraît identique.

Vérifier également les formats et shaders utilisés avec Sodium, et avec les shaders supplémentaires
s'ils sont actifs. Les couches ou contextes non validés gardent la voie existante. Un modèle animé
peut légitimement invalider son maillage fréquemment ; le cache ne doit pas le figer.

## Critères de validation

| Cas | Résultat attendu |
|---|---|
| Scène et éclairage stables | Aucun nouvel envoi des sommets après la construction initiale. |
| Déplacement, rotation et changement de perspective de la caméra | Même projection, sans reconstruction due à la caméra, fentes ou tremblement. |
| Relecture de lumière sans changement | Révision GPU inchangée. |
| Lampe posée, retirée ou déplacée ; lumière du dehors variable | Mise à jour selon la cadence actuelle, sans lumière figée ni pic disproportionné. |
| Lumière colorée | Couleurs et transitions équivalentes au rendu actuel. |
| Ressources rechargées, modèle changé ou ayant moins de faces | Nouveau modèle complet ; aucune ancienne face, texture ou référence conservée. |
| Pack utilisant une couche translucide | Chemin actuel utilisé, sans modification de la superposition. |
| Eau, mobs et particules près des parois ; modes graphiques disponibles | Même profondeur et même visibilité. |
| Entrées/sorties répétées, désactivation de l'éclairage, déconnexion | Pas de dessin résiduel ni d'accumulation de buffers GPU. |

Prévoir des tests automatisés pour les révisions, les faces supprimées, les transformations et le
cycle de vie des ressources. La fidélité du rendu et les interactions avec les mods exigent aussi
une comparaison en jeu.

Comparer des versions avant/après dans la même scène, avec caméra immobile puis mobile et éclairage
stable puis changeant. Relever le temps par image, le profil Spark client, le nombre de sommets,
les octets envoyés, les appels de dessin et les reconstructions. Distinguer le coût de la première
construction du coût continu. Les compteurs de diagnostic restent internes au développement.

## Fichiers concernés lors de l'implémentation

- `ProjectorLighting.java` : production du maillage, révisions et invalidations.
- Une classe dédiée, par exemple `ProjectorMesh.java` : propriété, envoi et libération des buffers GPU.
- `PlanariumProjectorRendererMixin.java` : délimitation de l'appel et choix du chemin.
- `ClientView.java` et le traitement de l'étape de rendu : dessin différé et rechargement des ressources.
- `SceneryReceiver.java` : nettoyage lors des transitions existantes.
- Les vérifications automatisées : régressions du cache et des transformations.

Le tri translucide général et les mises à jour partielles des buffers seraient des extensions
ultérieures, seulement si les mesures ou la compatibilité les justifient.
