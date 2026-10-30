# Occlusion des mobs du décor

L'occlusion est intégrée automatiquement au rendu des mobs du décor, sans option dans le menu
ni dans le fichier de configuration. L'ancien réglage expérimental n'est plus utilisé.

Le rendu du modèle d'un mob complètement masqué est sauté. Les copies continuent leurs animations,
les sons et les particules continuent, et les ombres restent dessinées. Les véhicules et les miniatures
ne sont pas concernés.
Les mobs lumineux, nommés ou tenus en laisse restent dessinés pour préserver les éléments qui dépassent leur corps.

La grille contient seulement les blocs pleins et opaques du terrain capturé. Le verre, les feuilles,
les dalles, les escaliers, les blocs à entité et les structures Sable ne bloquent pas la vue.
La caméra est transformée dans le même repère que les mobs, y compris sur un Planarium Sable.
L'occlusion est suspendue en vol, pendant la reconstruction des sections et si une reconstruction échoue.

Au plus huit mobs sont testés par image. Un résultat reste normalement valable huit images. Une preuve
d'occlusion couvre une petite marge autour de la caméra et du mob : s'ils sortent de cette marge, le mob
est réaffiché immédiatement ou retesté si le budget le permet. Une nouvelle capture ou correction du décor
invalide la grille et les résultats.

Les huit coins et le centre servent à rejeter rapidement les mobs visibles. Neuf rayons bloqués ne suffisent
pas à cacher un mob : une coupe complète du volume entre la caméra et sa boîte de rendu doit être opaque.
Une ouverture entre les rayons garde donc le mob visible. Certains reliefs irréguliers ne peuvent pas être
prouvés opaques par ce test et gardent leurs mobs dessinés : l'algorithme privilégie les faux visibles.

## Vérification en jeu

1. Activer « Faire vivre le dehors », puis régler « Mobs du dehors » sur 32. Préparer des mobs derrière
   un mur de pierre, avec assez de place entre eux et le mur pour leur boîte de rendu élargie.
2. Attendre l'apparition des mobs et la fin de la reconstruction du décor. Pour une comparaison de performance,
   utiliser une version sans occlusion et cette version avec la même scène, la même caméra, les mêmes shaders,
   la même limite de FPS et la même durée.
3. Refaire la comparaison à découvert, puis avec 64 mobs. Relever les temps par image et un profil
   Spark **côté client**, en particulier le fil de rendu. Un profil du serveur ne mesure pas cette optimisation.
4. Ouvrir une fenêtre dans le mur, déplacer la caméra et faire passer des mobs devant le mur. Ils doivent
   rester visibles dès qu'une partie apparaît. Le changement de mur suit la mise à jour habituelle du décor.
5. Vérifier le verre, les feuilles, les dalles, les escaliers, les passagers, les sons et les particules.
   Essayer aussi un Planarium sur une structure Sable en mouvement et le mode vol.

Les vérifications automatisées sont lancées par `checkSceneryOcclusion` et incluses dans `build`.
Elles ne remplacent pas une comparaison des performances ni une validation visuelle en jeu.
