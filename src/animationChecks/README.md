# Vérification des animations de mobs extérieurs

`gradlew.bat build` lance les contrôles suivants, également exécutables séparément :

- `checkOutsideMobAnimations` : protocole, chronologie, mains et durées d'attaque, reprise en cours d'animation, mort, doublons, débordement et interpolation des poses aquatiques.
- `checkOutsideMobIntegration` : méthodes de production de capture et de lecture, deux observateurs, isolation des mondes, poses immobiles, retrait différé, liens de monture, transitions eau/terre, nage verticale, poses des calmars, particules de potion, orientation des flammes, placement et atténuation des ombres, alignement mobs/terrain et restauration de la caméra après rendu.

- `checkMiniatureLive` : protocole léger, limites des paquets, comparaison des instantanés sans les déplacements, options, interpolation, nage, marche et propagation des tickets dans Minecraft (4 chunks au niveau 31, 12 au niveau 32 et 20 au niveau 33).

Les contrôles d'intégration et de miniatures remplacent les frontières du monde client et suppriment l'initialisation statique de certaines classes Minecraft dans un processus de test. Les méthodes de production restent intactes. Ces fixtures ne sont pas incluses dans le JAR du mod et ne remplacent pas une validation visuelle en jeu.

## Miniatures vivantes

Le flux optionnel `5.mini2` nécessite cette version côté client et serveur. Il transmet aussi la limite de mobs du joueur et son choix d'afficher les véhicules. Activer « Faire vivre les miniatures » dans le monde, puis vérifier :

1. Les mobs se déplacent dans la miniature proche ; marche, ailes et nage suivent leurs déplacements.
2. S'éloigner au-delà de 8 blocs, regarder ailleurs, désactiver le direct ou changer de dimension : retour aux animations de repos, puis reprise sans ancien déplacement.
3. Ajouter ou retirer un mob pendant le direct : apparition ou disparition dans la seconde. Vérifier aussi un bocal initialement vide, avec un four allumé.
4. Deux joueurs regardent le même bocal : un seul jeu de tickets ; le départ du premier ne l'arrête pas. Le départ du dernier retire les tickets, sauf si une autre source garde le bocal actif.
5. Atteindre la limite du serveur : les Planariums déjà actifs gardent leur place. Désactiver « Faire vivre les miniatures » : tous les tickets sont retirés, les mobs et véhicules sont masqués, et les trois options du joueur sont grisées et barrées. Le curseur des mobs affiche toujours sa valeur mémorisée (par exemple « 32 max »), tandis que les véhicules et le direct affichent OFF. Réactiver : les valeurs mémorisées reviennent.
6. Tester un Planarium sur un vaisseau Sable en mouvement : distance calculée dans le monde, arrêt à l'éloignement ou à la destruction du Planarium.
7. Placer plusieurs blocs liés au même intérieur : leurs miniatures proches partagent le direct et comptent pour un seul Planarium actif.
8. Régler les mobs sur 0, 8, 32 puis 64 : la limite change immédiatement. À 0, le direct affiche OFF, grisé et barré, même si les véhicules restent activés. Avec l'autorisation du monde, ces derniers restent visibles à leur dernière position. Vérifier un bateau occupé, un radeau, un wagonnet sur rails et leur dernière position après l'arrêt du direct.
9. Dépasser la limite de mobs puis en faire apparaître d'autres : les mobs déjà affichés gardent leur place. Le retrait d'un mob affiché libère une seule place. Répéter avec plusieurs copies du même Planarium.

Les instantanés complets restent disponibles pour les connexions et la capture finale. Pendant le direct, les changements de position, de rotation et de sol ne déclenchent plus leur diffusion ; les changements de liste ou d'apparence la déclenchent toujours.

Les quatre tickets de distance 2 donnent le niveau 31. Leur propagation couvre 4 chunks avec ticks d'entités, 12 voisins avec ticks de blocs et 20 autres chunks complets sans simulation : 36 au total, sur 6 × 6 chunks. Dans Minecraft 1.21.1, les constantes de `ChunkLevel` ajoutent 11 chunks de dépendances de génération autour du niveau FULL, jusqu'au niveau 44 : une emprise théorique de 28 × 28, soit 784 positions de chunks, dont 748 demandées à des stades de génération incomplets. Ce nombre décrit les niveaux demandés par les tickets, pas une mesure de mémoire en jeu ; d'autres tickets peuvent étendre la zone.

## Scénarios visuels

Utiliser cette version du mod côté client et serveur : le flux optionnel des entités utilise désormais `5.entities7` (limite de mobs choisie par le joueur).

1. Observer un mob immobile depuis le bocal, avec l'affichage des mobs activé, puis lui infliger des dégâts depuis le monde source : flash rouge bref, mouvement cohérent, pas de répétition à chaque paquet.
2. Observer les attaques d'un mob, puis celles d'un mob gaucher. Vérifier la progression complète du geste et la réaction de sa cible.
3. Tuer un mob : basculement jusqu'à la disparition, puis une seule bouffée de fumée au même endroit et à l'échelle du décor. Répéter avec les particules désactivées.
4. Faire sortir un mob du rayon, quitter le bocal ou désactiver l'option : aucune fumée de mort ne doit apparaître pour ces retraits.
5. Observer un bateau occupé, un cheval sellé avec passager, une araignée jockey et un poulet jockey. Tuer successivement le passager et la monture ; vérifier les positions et l'absence de passagers flottants.
6. Rejoindre une vue pendant une attaque ou une mort et faire observer la même scène par deux joueurs : pas de reprise de l'animation depuis son début ni d'événement consommé par un seul joueur.
7. Vérifier un coup bloqué/annulé et un mob sauvé par un totem : seules les réactions réellement émises par Minecraft doivent être reproduites.
8. Appliquer puis retirer des potions : particules de la bonne couleur à l'échelle du décor, arrêt des nouvelles particules après retrait. Vérifier les effets ambiants, l'invisibilité et les effets dont les particules sont masquées.
9. Observer un mob qui brûle, puis l'éteindre dans l'eau. Répéter en entrant dans le bocal alors qu'il brûle déjà : les flammes doivent disparaître après extinction. Incliner le Planarium et vérifier que les flammes restent tournées vers la caméra autour de la verticale du décor.
10. Observer un mob dans la neige poudreuse : tremblement une fois gelé et flash rouge lors des dégâts. Le faire dégeler et vérifier l'arrêt du tremblement. Comparer aussi les flashes de dégâts périodiques du feu avec ceux du monde source.
11. Observer un adulte et un bébé sur un sol plein, puis un mob qui saute : taille d'ombre adaptée, position sur le terrain capturé et atténuation avec la hauteur. Incliner et déplacer le Planarium : les ombres restent sur ce même terrain, sans retard supplémentaire.
12. Comparer les ombres de jour, de nuit et près d'une torche ; appliquer l'invisibilité et désactiver l'option Minecraft « Ombres des entités ». Vérifier leur disparition, puis leur retour après réactivation. S'éloigner jusqu'à 16 blocs du monde source pour vérifier leur disparition progressive.

13. Observer des morues, saumons, poissons tropicaux, poissons-globes et dauphins dans l'eau, puis hors de l'eau : posture de nage dans l'eau, posture terrestre hors de l'eau, queue des dauphins animée pendant leur déplacement. Faire gonfler un poisson-globe et vérifier sa taille.
14. Observer des calmars et calmars luisants : inclinaison du corps, rotation et tentacules fluides. Rester seul dans le bocal pendant plus de cinq secondes : les calmars observés continuent de nager. Blesser un calmar luisant dans l'obscurité et comparer son assombrissement puis sa récupération avec le monde source.
15. Observer des tortues et axolotls qui passent de l'eau à la terre, puis nagent verticalement. Vérifier les pattes, la posture au sol et l'animation d'un axolotl qui fait le mort.
16. Observer les animaux à travers l'eau et le verre : ils restent visibles par transparence, mais le terrain opaque les masque. Tourner la caméra et regarder vers le haut/bas : les animaux restent au même endroit dans le décor, sans glisser vers les bords de l'écran. Vérifier aussi les mobs terrestres, les bateaux et leurs passagers, puis incliner/déplacer le Planarium.

17. Observer deux colonnes d'eau, sur sable des âmes et sur magma : bulles montantes et tourbillons descendants visibles à travers l'eau, arrêt à la sortie de l'eau. Incliner/déplacer le Planarium et vérifier que les particules suivent le décor sans changer de vitesse relative.
18. Observer un dauphin qui nage puis s'arrête ou saute hors de l'eau : traînée derrière sa position interpolée uniquement pendant la nage. Blesser un calmar et un calmar luisant : un jet d'encre par émission réelle, de la bonne couleur et orienté avec le corps. Vérifier aussi les particules lumineuses du calmar luisant.
19. Désactiver les particules, quitter puis rejoindre le bocal : aucune ancienne bulle ni ancien jet d'encre ne réapparaît. Répéter avec deux observateurs et après une saccade.
20. Comparer un Drygmy et un Whirlisprig dehors et dans le décor : éclairage directionnel comparable. Tourner/incliner le Planarium et vérifier que les modèles vanilla gardent également leur éclairage.

21. Observer l'eau à travers sa surface et sous l'eau : les petites particules bleues d'ambiance restent visibles. Comparer les deltas de basalte, vallées des âmes, forêts carmin et biscornues : cendres/spores du biome source, y compris dans l'air, avec leur densité habituelle. Traverser une frontière de biome et désactiver/réactiver les particules du décor.
22. Observer un perroquet en vol puis posé, un poulet qui tombe puis atterrit et une chauve-souris qui quitte puis rejoint son repos : ailes animées en vol, transition vers le repos, reprise au décollage. Vérifier aussi un perroquet passager et comparer les abeilles, allays, vexes et phantoms, dont les ailes suivent déjà l'âge de rendu.

Les contrôles automatiques couvrent aussi les jets d'encre différés et uniques, la traînée des dauphins, les particules des calmars, les coordonnées de l'eau capturée, la durée de vie des particules, les densités d'ambiance des biomes, le passage de rendu sous-marin, les transitions des ailes et les normales d'éclairage des modèles agrandis.

Pour l'ambiance sous-marine, refaire le scénario 21 avec Sable installé : les particules doivent aussi être émises dans le faux monde du décor, qui n'a pas de conteneur d'occlusion Sable. Comparer l'eau source et l'eau qui tombe, puis vérifier que l'eau courante conserve ses sons. Le contrôle automatique couvre l'émission jusqu'au collecteur du décor, sa densité native et la délégation aux fluides des mods.

Les animations spécifiques aux boss ou aux systèmes propres aux mods seront traitées séparément.

Pour la sélection du décor extérieur, faire apparaître plus de mobs que la limite avant d'entrer dans le Planarium : leurs déplacements à l'intérieur de la zone ne doivent plus les remplacer. Seuls les départs de la zone et les disparitions libèrent des places, attribuées aux plus proches. Vérifier aussi les changements de limite, les resynchronisations et les groupes monture/passagers ; ces cas sont couverts par les contrôles automatiques.
