# Vérification des animations de mobs extérieurs

`gradlew.bat build` lance les deux contrôles suivants, également exécutables séparément :

- `checkOutsideMobAnimations` : protocole, chronologie, mains et durées d'attaque, reprise en cours d'animation, mort, doublons et débordement.
- `checkOutsideMobIntegration` : méthodes de production de capture et de lecture, deux observateurs, isolation des mondes, poses immobiles, retrait différé, liens de monture, particules de potion et orientation des flammes.

Le second contrôle remplace les frontières du monde client et supprime l'initialisation statique de certaines classes Minecraft dans un processus de test. Les méthodes de production restent intactes. Ces fixtures ne sont pas incluses dans le JAR du mod et ne remplacent pas une validation visuelle en jeu.

## Scénarios visuels

Utiliser cette version du mod côté client et serveur : le flux optionnel des entités utilise désormais `5.entities4`.

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

Les animations spécifiques aux boss ou aux systèmes propres aux mods seront traitées séparément.
