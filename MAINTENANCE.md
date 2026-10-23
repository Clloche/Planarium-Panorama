# Maintenance — Planarium Panorama

Document de développement, pas pour les joueurs (le README reste centré sur les fonctionnalités).
Il liste, pour chaque intégration : la version testée, ce qui dépend de détails internes d'un autre mod,
comment ça casse si ce mod change, et les tests à refaire après une mise à jour.

Révision du **2026-10-23**, pour l'état du code **1.6.1-dev**, y compris le correctif du portable engine.
La précédente modification du document (`0db1153`, 2026-10-22) couvrait surtout l'immersion et le ciel incliné.
Cette révision reprend aussi les changements depuis la 1.5 : brouillard, découpe, Planariums voisins,
optimisations de la 1.6, contraptions assemblées et rafraîchissement des structures en mouvement.

## Versions testées et références retrouvées

Relevé actualisé le 2026-10-23, à partir du README, des JAR locaux et des journaux conservés.
Une version chargée dans un journal confirme la présence du mod, pas la validation de toutes ses fonctions.
Les références historiques et les JAR désactivés restent identifiés séparément ; aucun nouveau test en jeu
n'a été effectué pour rédiger cette mise à jour.
La date ci-dessous est celle de la source ou du relevé du JAR ; « — » signifie que la date du test n'est pas conservée.

| Mod | Version de référence | Source / état | Date de la source |
|---|---|---|---|
| Minecraft / NeoForge | 1.21.1 / 21.1.228 | Chargés dans la session locale du 23 | 2026-10-23 |
| Ars Nouveau | 5.13.3 | Chargé dans la session locale du 23 | 2026-10-23 |
| Sable | 2.0.6 | Chargé le 23 ; référence antérieure : 2.0.4 le 21 | 2026-10-23 |
| Sable Companion | 1.6.0 | Chargé le 23 ; dépendance embarquée de Sable | 2026-10-23 |
| Ars Sable | 1.2.0 | Chargé dans la session locale du 23 | 2026-10-23 |
| Create | 6.0.10 | Chargé dans la session locale du 23 | 2026-10-23 |
| Flywheel | 1.0.6 | Chargé le 23 ; dépendance embarquée de Create | 2026-10-23 |
| Create Aeronautics / Simulated / Offroad | 1.3.2 | Chargés le 23 depuis le bundle Aeronautics | 2026-10-23 |
| Contraption Lights | 1.5.2 ; ancienne référence : 1.5.4 | 1.5.2 chargé le 23 ; 1.5.4 dans le log du 17 | 2026-10-23 / 2026-10-17 |
| Contraption Lights Compat / Fixes | 2.4.0 / 1.5.0 | Présents dans le pack du 23 ; à conserver dans le contexte d'un rapport de rendu | 2026-10-23 |
| Dynamic Surroundings | 0.4.6 | Chargé dans la session locale du 23 | 2026-10-23 |
| Enhanced Celestials 2 (Core) | 2.0.3.3 | Chargé dans la session locale du 23 | 2026-10-23 |
| Enhanced Celestials 2 (Default Lunar Events) | 2.0.1.3 | Chargé dans la session locale du 23 | 2026-10-23 |
| Puddles & Floods | 1.1.6 | Chargé dans la session locale du 23 | 2026-10-23 |
| YetAnotherConfigLib | 3.8.2 | Chargé dans la session locale du 23 | 2026-10-23 |
| Better Clouds | 1.13.11 | Chargé dans la session locale du 23 | 2026-10-23 |
| AmbientSounds | 6.3.10 | JAR local désactivé (`.jar.disabled`), absent de la liste des mods chargés le 23 | 2026-10-23 |
| Falling Leaves | 2.6.0 | README et JAR de test ; ancien log : 2.5.1 | — |
| Snow! Real Magic | 12.2.3+neoforge | Version chargée dans un ancien log | 2026-10-17 |
| Serene Seasons | 10.1.0.3 | Version testée indiquée dans le README | — |
| Alex's Caves | 2.0.2 | Liste des mods d'un ancien rapport de plantage | 2026-10-15 |
| Distant Horizons | 3.3.3 | Version testée indiquée dans le README | — |
| Traveler's Titles | 5.1.3 | Version testée indiquée dans le README | — |
| Voxy (NeoVoxy) | 0.5.0-alpha.12 | Référence du README et des sessions du 21 ; absent de la liste chargée le 23 | 2026-10-21 |
| Simple Clouds | Non relevée | Aucun JAR ni numéro de version retrouvé dans les sources consultées | — |
| Windy: Configurable | 1.2.1 | README ; ancien log : 1.2.0 | — |
| Swinging Lanterns | 2.0.4 | Version testée indiquée dans le README | — |
| Sodium | 0.8.13 | Chargé dans la session locale du 23 | 2026-10-23 |
| Jaden's Nether Expansion | 2.4.1 | Version testée indiquée dans le README | — |
| AmbientEnvironment | 18.0.0.2 | Chargé le 23 ; correctif des raccords de teinte en vol dans `73ac734` | 2026-10-23 |
| Lithium | 0.15.4 | Chargé le 23 ; utile pour reproduire les collisions des fumées | 2026-10-23 |

Sources locales utilisées : `README.md`, `run/mods`, les métadonnées des dépendances embarquées,
`run/logs/latest.log`, `build/sublevel-repro/logs/latest.log`, `run/logs/2026-10-17-12.log.gz`
et `run/crash-reports/crash-2026-10-15_14.20.22-client.txt`.
Les chemins dans `run` et `build` servent de provenance locale et peuvent disparaître lors d'un nettoyage.
Pour une prochaine mise à jour, relever la version effectivement chargée dans le log en plus du nom du JAR.
Le journal du 23 utilisé ici est celui du lancement à 14:54, avec Planarium Panorama `1.6.1-dev+1b09dde`.
Le dépôt source fourni pour Aeronautics (`Simulated-Project-main(1).zip`) a aussi servi à vérifier le portable engine ;
ses méthodes ont été recoupées avec le JAR Simulated 1.3.2 embarqué. Cette archive externe n'est pas une dépendance du projet.

## Repères des changements à maintenir

| Période | Changements et références Git |
|---|---|
| 1.5 / 1.5.1 | Immersion et ciel incliné (`0db1153`), brouillard liquide DS (`bb28d36`), miniature partagée (`bc2f6a1`), copie CPU pour la découpe (`f4b250e`), raccords des sommets (`78b7ed4`) |
| Préparation de la 1.6 | Rayon détaillé cylindrique (`16ed068`), Planariums voisins (`8024021`, `03fbb88`, `8b7d690`) |
| 1.6 | Culling du décor et du vol (`2eea05b`, `aef8061`), préparation asynchrone de l'anneau (`9553608`), cache de lumière Minecraft (`184bde2`), compatibilité Contraption Lights automatique (`b926b63`) ; version publiée dans `7497ec1` |
| 1.6.1-dev | Allègement des couleurs en vol et brouillard des block entities (`4e748d4`), contraptions assemblées et pivot partagé des voiles (`570e9dd`), captures des structures après stabilisation (`1b09dde`), correctif d'animation et de particules du portable engine |

Ces références servent à retrouver les décisions techniques. Elles ne constituent pas un relevé de tests en jeu.

## Comment une intégration peut casser

Quatre cas, du plus visible au plus dangereux :

1. **Plantage au démarrage** : mixin obligatoire dont la cible a disparu. Message clair, facile à voir.
2. **Désactivation avec avertissement** : la réflexion échoue, un avertissement est écrit une fois dans le log.
3. **Désactivation silencieuse** : mixin en `require = 0` qui ne s'applique plus, ou réflexion qui échoue sans log
   (ou seulement en DEBUG). La fonction disparaît sans signe : seul un test en jeu la révèle.
4. **Écriture en mémoire native** (Flywheel) : un mauvais calcul n'est pas une exception récupérable.
   Affichage corrompu ou plantage possible.

### Avertissements : règles

- Avertir seulement quand **le mod est installé mais incompatible**. Un mod absent ou une intégration désactivée
  volontairement (option sur OFF) ne doit rien écrire.
- Un `catch` ne détecte pas une injection de mixin qui ne s'applique plus. Pour ces cas, un indicateur mis à vrai la
  première fois que l'injection s'exécute peut aider, **mais un indicateur encore à faux ne prouve rien** : il faut aussi
  savoir que l'action censée déclencher l'injection a eu lieu. Sinon : avertissements injustifiés au démarrage.
  (Exemple : vérifier un crochet de couleur après avoir effectivement demandé un calcul de couleur.)

## Par intégration

### Ars Nouveau (dépendance obligatoire, la plus exposée)

Dépend de :

- Mixins sur des classes internes : `PlanariumRenderer`, `PlanariumRenderer.StructureRenderData`,
  `PlanariumRenderingWorld`, `PlanariumProjectorRenderer`, `PlanariumTile`, `DimBoundary`.
- Beaucoup d'injections sont en `require = 0`. Certaines sont obligatoires, notamment
  `PlanariumTileSubLevelMixin` et celles sur `tickRateManager` et les deux surcharges de `getEntities` dans
  `PlanariumRenderingWorldMixin`.
  Une mise à jour peut donc **planter** (injections obligatoires) **ou désactiver en silence** (les autres).
- Constantes recopiées à la main dans `ClientView` : `PAD`, `ARS_SCALE`, `ARS_OFFSET`, `GIANT`, `Y_SHIFT = 25`
  (échelle et position du rendu d'Ars). Si Ars les change, rien ne plante : le décor est seulement décalé.
- Format de la dimension de poche (bocal de 32 × 32, parois `ars_nouveau:dim_boundary`, `PlanariumChunkGenerator.innerBox`).
- Contrat du faux monde : les recherches d'entités doivent renvoyer une liste vide, pas `null`.
  Le portable engine appelle `getEntitiesOfClass(Player.class, ...)` pour sa trappe ; avec Ars 5.13.3,
  les deux surcharges de `getEntities` renvoient `null`. Le mixin corrige ces retours dans les copies.
  Ne pas les rediriger vers les joueurs du vrai monde : les positions appartiennent au repère de la copie.

Tests :

- Décor géant aligné contre les parois et sous le plancher (pas de trou ni de chevauchement au bord).
- Miniature : lumière réelle (pas 15 partout), ombrage des coins normal, parois en verre complètes.
- Parois et plancher de l'intérieur éclairés (`ProjectorLighting`), plateau de la miniature éclairé par un feu dans le bocal.
- Planarium sur un vaisseau Sable : le bocal trouve son Planarium.
- Plusieurs Planariums vers le même bocal : pas de reconstruction de la miniature chaque seconde en alternant les vues.

### Sable / Sable Companion

Dépend de (tout par réflexion, `SubLevelCompat`) : `SubLevelContainer`, `SubLevel`, `ServerSubLevel`, `ClientSubLevel`,
`LevelPlot`, `PlotChunkHolder`, `Pose3dc.bakeIntoMatrix`, `BoundingBox3ic/3dc`, tickets de chargement
(`SubLevelLoadingTicketType`, `addForceLoadTicket`…), points de suivi (`SubLevelTrackingPointSavedData`),
`SubLevelHoldingChunkMap.snatchAndLoad`, `SubLevelRenderData.setDirty` (via `SubLevelLight`),
`SubLevelTicketsSavedData`.

Casse : désactivation de tout le support Sable avec avertissement (« Unsupported Sable API… »).
Confirmer au lancement : `[Planarium Panorama] Sable sub-level support enabled`.

Contournement d'un bug de Sable : `forgetTracking` (Sable 2.0.4 envoyait des suppressions au joueur qui change de
dimension). Vérifier à chaque version s'il est encore nécessaire ; sans danger s'il ne l'est plus.

Captures et protocole (`SubLevelServer`, `SubLevelView`, `SubLevelPayload`) :

- Géométrie et poses voyagent séparément. Les poses sont envoyées toutes les 2 ticks ; le client les interpole sur
  100 ms. Les matrices restent en double précision (`SubLevelTransforms`) pour les plots éloignés de Sable.
- Le déplacement seul ne marque plus les sections comme modifiées. Après 20 ticks de stabilité, leur lumière est
  reprise si leur pose a changé depuis la capture. Les vrais changements de blocs et le rafraîchissement de secours
  restent pris en compte selon `blockUpdateSeconds` et `refreshSeconds` : ce n'est pas un arrêt général des mises à jour.
- Les recaptures différées partagent un budget de 2 ms par appel ; au moins une est autorisée, les sections nouvelles
  passent immédiatement. Ce budget n'est donc pas une limite stricte pour une première capture importante.
- La liste des sections visibles précède les paquets de géométrie pour libérer les anciennes sections côté client.
  Conserver les renvois après connexion/changement de dimension et la limite commune de 256 sections par scène.
- Paquets NBT compressés : 1 000 000 octets maximum sur le réseau, limite de lecture décompressée de 32 Mio.
  Les champs facultatifs de pose doivent garder un repli pour les clients qui ne les interprètent pas.

Tests :

- Vaisseau visible dans le décor géant et la miniature, qui bouge et tourne (90°, 180°, 45°).
- Planarium incliné : soleil, lune, étoiles et nuages alignés avec le décor (lacet, tangage et roulis).
- Planarium immergé par le mouvement du vaisseau : brouillard d'eau ou de lave, ambiance sous-marine dans l'eau,
  puis retour à l'air en ressortant. Le joueur reste physiquement au sec dans le bocal. Les effets des liquides
  personnalisés ne sont pas encore pris en charge ; le petit échantillon de fluides voyage dans le champ NBT
  facultatif `fluids` des poses Sable, y compris quand les copies de structures sont désactivées.
- Découpe du décor contre le volume intérieur du bocal (`ClippedVertexBuffer`, `JarClip`) : liquide traversant
  une paroi avec un roulis de 70°, puis tangage et déplacement. Les faces extérieures et leurs UV doivent rester
  intactes ; le sol du bocal reste visible. Vérifier aussi le passage au décor en vol et le retour à la photo normale.
  Cette découpe concerne les maillages du décor, pas les rendus spéciaux de block entities (volant d'Aeronautics).
- Planarium sur un vaisseau : déplacement (décor en vol), arrêt (photo normale puis anneau lointain 5 s plus tard).
- Sortie du bocal pendant que le vaisseau bouge, puis retour.
- Déconnexion à l'intérieur du bocal sur un vaisseau, puis reconnexion : le vaisseau et la vue reviennent.
- Vaisseau à l'intérieur du bocal : éclairé comme le bocal (`SubLevelLight`), pas comme son plot.
- Déplacement prolongé avec beaucoup de sections : pas de recapture complète à chaque changement de pose,
  ni saccade périodique accompagnée d'une coupure des sons. À l'arrêt, la lumière doit finir par se mettre à jour.
- Modifier réellement un bloc du vaisseau ; comparer les options de mise à jour et de secours sur ON puis OFF.
- Block entities du vaisseau (moteur, volant, hélice) : même brouillard que les blocs voisins, y compris sous l'eau.

### Ars Sable

Dépend de (réflexion, `ArsSableCompat`) : `SublevelPosData.from`, `getForPlayer`, `Entry.trackedPos`, `Entry.sublevelId`.
Casse : avertissement, et le suivi du vaisseau après reconnexion se rabat sur la recherche normale.
Tests : les mêmes reconnexions que Sable.

### Planariums voisins (Ars Nouveau)

Implémentation : `NeighborPocketsServer`, `NeighborPocketsPayload`, `NeighborPrefsPayload`, `NeighborPockets`
et `NeighborPlanariumModel`. Dépend de `PlanariumTile.key`, du format des poches, du modèle du Planarium et des
captures de blocs/lumière de `VisibleCapture`. C'est une photo visuelle figée de la poche voisine, sans simulation
ni animation de ses machines.

- Recherche dans le cylindre du rayon détaillé, tri par proximité, au plus 10 voisins. Option client
  `neighborPlanariums` : 4 par défaut, 0 = OFF ; la préférence est transmise au serveur pour limiter aussi la capture.
- Photos en cache par dimension de poche ; les changements de blocs invalident ce cache pour les prochaines captures.
  Le cache serveur conserve au plus 64 photos, dans l'ordre des accès : une lecture avec `CACHE.get()` conserve
  une poche parmi les plus récemment utilisées, et la moins récemment utilisée est évincée à la 65e insertion.
  Ne pas promettre une mise à jour en direct d'une photo déjà affichée pendant la visite.
- Sur Sable, la recherche se fait autour de la position du bloc dans son plot : elle concerne les Planariums de
  cette même structure. Au sol, elle concerne ceux du sol.
- Les parois partagées avec les voisins affichés sont masquées. Le modèle du Planarium géant doit suivre les mêmes
  ouvertures ; son plancher et son verre utilisent la lumière de la poche correspondante.

Tests : deux bocaux adjacents puis en diagonale, voisins à des hauteurs différentes, torche dans une poche,
options 0/1/4/10 pendant la visite, plusieurs joueurs avec des limites différentes, sortie/rentrée après modification.
Vérifier l'absence de verre noir, de mur opaque entre bocaux adjacents et de doublon de coque.

### Flywheel (risque le plus grave)

Dépend de : `LightStorage.SOLID_SIZE_BYTES` et du **format interne** des données de lumière (18 × 18 × 18 octets par
section, blocs dans les 4 bits bas, ciel dans les 4 hauts, après le bloc des blocs pleins), via
`FwLightDataCollectorMixin` → `FlywheelCompat.boostSection` qui écrit directement en mémoire (`MemoryUtil`).
Aussi : `VisualizationManager.get/blockEntities`, `queueRemove/queueAdd`, `onLightUpdate`.

Casse : si le format change, l'écriture tombe au mauvais endroit. **Pas d'exception récupérable** : affichage corrompu
ou plantage. Vérifier la version de Flywheel ou certaines tailles avant d'écrire réduirait le risque, mais ne remplace
pas le test.

À faire : vérifier `missing` au début de `boostSection` ; noter ici la version dont le format a été vérifié.

Tests (obligatoires à chaque mise à jour de Flywheel ou de Create) :
- Coffre, lit, engrenage de Create dans le bocal près d'une torche et près d'une paroi éclairée : même lumière que les
  blocs autour, et change quand la lumière du dehors change (jour/nuit).

### Create

Dépend de : `SmartBlockEntity.markVirtual()` et `tick()` (mode virtuel de Ponder) dans `CreateCopies` ;
`SoundScapes` (`CreateSoundScapesMixin`). Le mode virtuel a déjà surpris une fois (sifflets alimentés sans redstone).
Tests : machines animées dans le décor et la miniature, sons des machines, sifflet non alimenté silencieux.

Les copies restent virtuelles et attachées au faux monde. Ne jamais réattacher ni faire tourner la block entity
réelle. L'animation doit continuer si les sons ou les particules sont désactivés ; `CopyParticles` s'occupe des
effets visuels, `MachineSounds` des sons. Les particules des copies suivent leur repère et leur échelle via
`FramedParticle`, et les émetteurs sont oubliés quand la copie n'est plus affichée.

#### Contraptions assemblées sur Sable

`ContraptionCapture` lit par réflexion `AbstractContraptionEntity.getContraption()`, `Contraption.getBlocks()`
et `AbstractContraptionEntity.toGlobalVector(Vec3, float)`. Après assemblage, ces blocs sont dans une entité Create,
plus dans les chunks du plot : capturer uniquement le plot fait disparaître hélices, voiles et autres mécanismes.

- Les sections sont capturées dans le repère local de la contraption. Une signature des états/NBT déclenche une
  nouvelle capture si les blocs changent ; tourner ou se déplacer ne suffit pas à reconstruire leur géométrie.
- Chaque contraption réserve ses propres origines factices (`y = -1 000 000`, intervalle en x avec marge de 64 blocs).
  Garder ces espaces distincts : sinon les tables de voisinage et de lumière confondent des mécanismes différents.
- Les champs NBT `group`, `group_pose` et `local` relient les sections au même pivot, centré au milieu du bloc
  d'ancrage. Le client interpole une seule rotation puis applique les positions locales. Revenir à une interpolation
  indépendante des coins fait réapparaître le « wobble » : les sections des voiles se séparent pendant la rotation.
  Le champ `pose` reste envoyé pour le repli.
- Casse : avertissement `Unsupported Create API; contraptions on sub-levels are not shown` si les signatures
  changent ; une contraption individuelle peut aussi être ignorée avec seulement une trace DEBUG.

Tests : assemblage/désassemblage d'une hélice, voile traversant plusieurs sections de 16 blocs, plusieurs mécanismes
côte à côte, rotation du mécanisme pendant le déplacement et l'inclinaison du vaisseau, modification d'un bloc
assemblé, sortie/rentrée et reconnexion. Vérifier le décor géant et la miniature, les raccords et la lumière.

**Correctif appliqué — invalidation de la lumière des contraptions (régression de `f0b8f8f`, 2026-10-23).**
Les sections de contraptions gardent `Part.origin = null`, mais `SubLevelServer.dirty()` conditionnait aussi
le test de `worldBounds` à `origin != null`. Les changements à proximité ne les marquaient donc plus comme
modifiées et leur éclairage pouvait rester ancien jusqu'au rafraîchissement de secours.
Les tests de proximité dans le plot (`origin`) et dans le monde physique (`worldBounds`) sont maintenant
indépendants, chacun avec sa propre vérification de nullité.

Test à effectuer : garder une contraption assemblée immobile, activer les mises à jour de blocs et désactiver le
rafraîchissement de secours, puis poser et retirer une source lumineuse près de ses limites physiques. Sa copie
doit changer d'éclairage après le délai de mise à jour des blocs, sans sortir du bocal ni réassembler le mécanisme.
Correctif identifié par lecture du code ; validation en jeu encore à faire.

### Create Aeronautics / Simulated

Dépend de : noms de classes (`HotAirBurnerBlockEntity`, `BasePropellerBlockEntity`, `SmartPropellerBlockEntity`),
champ public `prop`, méthodes `isActive()`, `spawnParticles()` (`CopyParticles`), son du brûleur (`MachineSounds`).
Le brûleur a aussi des crochets de capacité, signal, intensité et sortie de gaz, et utilise
`HotAirEmberParticleData(boolean)`. Son rendu immédiat change l'état OpenGL : `CreateCopies.render` restaure
le shader, le test de profondeur et le culling après son rendu.
Casse : avertissements `Burner preview particles unavailable`, `Propeller preview particles unavailable`,
ou désactivation/silence selon le crochet concerné.
Tests : brûleur (flamme, son), hélices (rotation, particules) dans les copies.

Portable engine (Simulated 1.3.2) :

- Classe `dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity` ; son `tick()`
  client anime `visualSpeed`, `visualStrength` et la trappe. L'état provient notamment de `BurnTime`,
  `GeneratedSpeed` et `SuperHeated` dans le NBT capturé.
- Le journal du 23 a montré une `NullPointerException` dans `updateHatchTime` : `players` était nul. `CreateCopies`
  désactivait ensuite les animations de cette classe (`Create preview animation skipped`). Le correctif des
  recherches d'entités de `PlanariumRenderingWorldMixin` permet au tick virtuel de continuer.
- Le tick natif ignore `spawnParticles()` en mode virtuel. `CopyParticles` appelle cette méthode une fois par tick
  d'émetteur visible si `getCurrentBurnTime() > 0` : mêmes sorties, fréquences et variantes de flammes que le mod.
  On garde le mode virtuel ; la copie ne doit ni consommer du carburant ni simuler le réseau mécanique.
- Casse : `Portable engine preview particles unavailable` si ces méthodes changent. Une erreur du tick peut de
  nouveau arrêter toute l'animation de la classe. La trappe n'a pas de joueur local à détecter dans la copie.

Tests à refaire après le correctif : moteur éteint/allumé/surchauffé dans le décor et la miniature ; pulsation et
chauffe des tuyaux, fumée aux deux sorties, arrêt de la fumée moteur éteint, effets placés et redimensionnés avec le
vaisseau en mouvement. Désactiver les particules puis les sons séparément : l'animation doit rester active.
Vérifier que les deux avertissements précédents n'apparaissent plus. La compilation du correctif a réussi le 23 ;
la validation visuelle complète reste à consigner.

### Contraption Lights

Dépend de : `ColorLightEngine.colorSection(Scratch, BlockAndTintGetter, int, int, int, boolean)` (signature exacte,
`ClColorSectionMixin`), `ColorLightEngine.invalidate`, `clear`, `ColorLightFeature.tag`, `tagBaked`, `baking`
et du format des couleurs dans la lumière (`ClTag`). `ClColorFeatureMixin` intercepte aussi
`tag(int, BlockAndTintGetter, int, int, int, boolean)` pour les constructions sans couleur.
Casse : la redirection des couleurs du décor est désactivée avec un avertissement (plus de repli sur `setSource`).
Les crochets de `ClTag` peuvent échouer silencieusement ; les tests visuels restent nécessaires.

Depuis la 1.6, la compatibilité est automatique si Contraption Lights est installé : pas d'ancienne option de
couleurs du décor à réactiver. Les réglages du mod Contraption Lights restent à considérer.
`ColorCompat` garde un contexte par thread et un emplacement de cache par faux monde, réutilisé entre ses sections
et ses rendus spéciaux. Les modifications dans un monde existant invalident ses sections avec `blocksChanged`.
Ne pas vider le cache global à chaque construction ni remplacer la source globale avec `setSource`.

Les sections du **terrain en vol** utilisent volontairement `beginWithoutColor()` pour éviter les recherches de
sources à chaque section. Leur éclairage vanilla reste actif ; le décor normal et les copies de structures ont
leur propre traitement. Ne pas présenter l'absence de couleur du terrain en vol comme une panne de l'intégration.

Tests : torche colorée dans le décor géant (pas de clignotement blanc), dans la miniature et près des parois ;
glowstone et block entities colorées ; changement d'une source lumineuse sans ancienne teinte conservée ; pluie,
entrée en vol puis retour à l'arrêt sans saccade de recherche de couleurs ni perte de couleur du bocal.

### Dynamic Surroundings (le plus de points d'accroche)

Dépend de : nombreux mixins `Ds…` (scanners de biome et de plafond, variables de biome et météo, saisons, sons bloqués,
lucioles, dimensions) et champs privés lus par nom : `dimensionRules`, `configuredCloudHeight` (`DsAltitude`),
`obstacles`, `TAG_LIBRARY` (`DsFirefliesCompat`), `growthRate` (`DsRipplesCompat`).
`DsFogCalculatorMixin` cible `HolisticFogRangeCalculator.render` : garder le brouillard liquide du bocal immergé,
même si le joueur y est au sec. Sinon DS peut le recalculer ou inonder le journal d'avertissements par image.
`DsBiomeScannerMixin` doit aussi éviter de relancer ses recherches sur un faux monde à chaque lecture de biome.
Casse : surtout en silence.
Tests : ambiance du biome du dehors dans le bocal, silence sous abri, sons de grotte sous terre, lucioles dans le décor,
cascades du décor, pluie du dehors, immersion dans l'eau et la lave, sortie à l'air et absence de spam de brouillard.

### AmbientSounds

Dépend de : mixins `As…` (moteur, environnement, terrain, biome) et un champ privé (`AmbientSoundsCompat`).
Tests : sons du biome et du terrain du dehors dans le bocal.

### Falling Leaves, Snow! Real Magic

Champs privés : `SpriteContents.byMipLevel` (Minecraft, lu par la compatibilité Falling Leaves), `variantModel`, `wrapped` (Snow! Real Magic). Casse en silence.
Tests : feuilles qui tombent dans le décor et la miniature ; neige sur un bloc dans le décor.

### Enhanced Celestials 2, Serene Seasons, Puddles & Floods, Alex's Caves, Distant Horizons, Traveler's Titles, YACL

Mixins ciblés (`EcForecastMixin`, `DsSereneSeasonsMixin`, `PfPlacementMixin`, `AcAmbersolLightMixin`,
`DhClientApiMixin`, `TravelersTitlesMixin`, `Yacl…`) en `require = 0`. Casse en silence.
Tests rapides : lune de sang du dehors dans le bocal ; couleurs de saison ; flaques dans le décor ; pas de lumière
fantôme sous l'ambersol ; pas de bande de terrain lointain DH autour du bocal ; pas de « ??? » en entrant ;
menu de config (boutons, groupes, sauvegarde immédiate, Échap).

## Rendu, captures et performances

- **Forme du détail** : `DetailShape` définit le cylindre commun aux captures et au rendu. Tester les diagonales et
  la frontière détail/anneau/vol ; un critère carré réintroduit des blocs dans les coins ou des trous de raccord.
- **Précision des sommets** : `GridSnap.snapMesh` remet les positions sur une grille de 1/4096 de bloc source avant
  le tri des transparents et l'upload. Cela ferme les fissures qui laissaient scintiller des pixels de ciel.
  Garder l'opération sur le maillage fini pour préserver les chemins optimisés de `VertexConsumer`.
- **Culling** : `SceneryFrustum` traite le décor détaillé, l'anneau et le vol, que Sodium ne peut pas éliminer à notre
  place. Les boîtes viennent des vrais sommets, avec la même matrice que le dessin, inclinaison Sable comprise.
  Invalider les listes visibles après ajout/retrait/reconstruction ; une boîte inconnue doit rester visible.
- **Découpe** : `ClippedVertexBuffer` garde une copie CPU des sommets à l'upload pour éviter la lecture GPU au premier
  découpage. Cette copie peut être libérée loin du bocal ; `glGetBufferSubData` reste un repli si la section doit être
  découpée plus tard. Libérer aussi les copies CPU, buffers de travail et maillages découpés à la fermeture.
- **Anneau asynchrone** : `FarRing` prépare palettes et tables sur son exécuteur ; l'installation, les maillages et
  les objets OpenGL restent sur le fil de rendu. `loadVersion` rejette les résultats devenus obsolètes après une
  nouvelle capture, un changement de monde ou une désactivation. Ne pas lire un monde vivant depuis cet exécuteur.
- **Cache Minecraft** : les constructions d'anneau, de terrain en vol et de poches voisines partagent le cache de
  lumière/ombrage de `ModelBlockRenderer` pendant une construction, puis appellent `clearCache` dans un `finally`.
  Ses clés sont des positions : conserver le cache entre deux faux mondes mélange leurs éclairages.
- **Budgets** : `CaptureBudget` partage 6 ms par tick serveur entre les captures qui l'utilisent ; le client construit
  progressivement anneau et voisins (2 ms par image chacun), et vol (2,5 ms). Une section individuelle peut dépasser
  ce budget. Une accumulation ne prouve donc pas à elle seule une fuite ni un respect strict du temps d'image.
- **Cache lointain** : les changements invalident les photos concernées ; leur âge est aussi borné. À l'arrêt du
  Planarium, le terrain normal revient et l'anneau attend 100 ticks de stabilité. Vérifier les tickets libérés à la
  sortie et à l'arrêt serveur, ainsi que la reconnexion et le changement de rayon.
- **Miniatures de sublevels en mouvement** : `Part.mini()` peut reconstruire le maillage et son contexte de couleurs
  toutes les 20 ticks si la pose a changé. L'ancien maillage est fermé : ce sont des allocations répétées, pas une
  accumulation de maillages. Cela concerne le bocal vu de l'extérieur, avec une structure Sable qui bouge dedans.
  Réutiliser le contexte de couleurs seulement si un profilage montre que ces allocations posent problème.

Diagnostic des sections lentes : `FarRing` journalise les constructions de plus de 20 ms avec leurs étapes
(tessellation, maillage, tri, upload). Ajouter l'argument JVM `-Dplanariumview.diagSections=true` pour obtenir
les coûts par type de bloc. Relever le mode affiché (normal/anneau/vol), les rayons, les mods chargés et l'action
qui déclenche la saccade avant de comparer les mesures.

Tests de rendu : tourner rapidement la caméra, modifier le FOV, incliner le vaisseau, regarder un modèle qui
dépasse de son bloc, passer près d'une paroi avec eau/verre, répéter entrée/sortie et marche/arrêt du vol.
Vérifier l'absence de disparition au bord de l'écran, de fissures blanches, de gel au premier découpage et de
vieille photo installée après avoir changé de monde. Refaire ces cas avec et sans Sodium ; les références Voxy
du tableau ne remplacent pas un nouveau test de cette version.

## Règles générales

- **Fils d'exécution** : la comparaison des photos (`ClientView`, `DIFF_THREAD`), l'instantané de lumière lu par les
  fils de Sable (`SubLevelLight`) et les couleurs du bocal (`JarColor`, `WallColors`) travaillent sur des copies figées.
  Sur ces fils, ne jamais lire le monde ou le moteur de lumière directement.
- **Fin d'un monde** : tout état statique côté serveur doit être vidé à l'arrêt du serveur (`onServerStopping`),
  sinon il passe au monde suivant en solo. Fait : scènes Sable, tickets et caches de l'anneau, du vol et des voisins
  (`SubLevelServer.clear`, `FarRingServer.clearAll`, `FlightServer.clearAll`, `NeighborPocketsServer.clearAll`).
  À faire : `RELIT`, `FLOOR_CLEANED`, `MINI_LIGHT`, `MINI_ENTITIES`, `MINI_ENTITIES_DUE`, `MINI_ENTITIES_NEXT`.
- **Caméra des copies** : `CreateCopies.clear()` remet aussi `cameraProxy` à `null` pour libérer sa référence au
  monde client après déconnexion. Une recréation au prochain monde ne suffit pas à libérer le précédent au menu.
- **Messages du client** (`ViewTogglePayload`, `SoundPrefsPayload`, `SubLevelPrefsPayload`, `NeighborPrefsPayload`) : les valeurs identiques
  sont déjà filtrées par endroits, mais des bascules ON/OFF répétées forcent des captures complètes ou des changements
  de tickets. Sur un serveur public : limiter à un changement par seconde par joueur.
- **Tests du dépôt** : `src/test` n'est plus suivi par Git ; `checkSubLevels` est sauté s'il est absent. Garder les
  tests en local et les lancer avant une publication.
- **Protocole et serveur dédié** : garder les canaux facultatifs et les vérifications `hasChannel` ; ne pas introduire
  de classe cliente dans les captures serveur. Inclure une connexion multijoueur et un lancement dédié dans les
  vérifications de publication après modification des paquets ou de la compatibilité par réflexion.

## Vérifications de cette révision

- Le 2026-10-23, `gradlew.bat --offline build` a réussi sur le code incluant les corrections de voile et du portable
  engine. `compileTestJava` et `test` étaient `NO-SOURCE`, `checkSubLevels` était `SKIPPED` : ce résultat confirme
  la compilation et la production du JAR, pas le comportement en jeu.
- Le journal du lancement du 23 à 14:54 confirme les versions chargées listées plus haut. Les anciennes références
  du README, les JAR désactivés et les cas de la liste ci-dessous restent à revalider visuellement.
- Cette modification de documentation a été recoupée avec les sources et l'historique ; elle n'ajoute aucun résultat
  de test manuel au relevé.

## Après une mise à jour du modpack (liste courte)

1. Lancer le jeu et lire le log : support Sable activé ; aucun avertissement « … indisponible / unsupported ».
2. Décor géant aligné avec les parois (Ars Nouveau).
3. Coffre et engrenage éclairés dans le bocal (Flywheel).
4. Torche colorée dans le décor (Contraption Lights).
5. Sons du biome du dehors (Dynamic Surroundings, AmbientSounds).
6. Machine de Create, brûleur, hélice et portable engine : animation, lumière et particules dans le décor et la miniature.
7. Voile assemblée sur plusieurs sections : rotation commune sans « wobble », assemblage et désassemblage visibles.
8. Vaisseau : déplacement, arrêt, lumière après stabilisation, sortie, déconnexion/reconnexion (Sable, Ars Sable).
9. Planariums voisins : limite client respectée aussi à la capture, verre partagé masqué, planchers éclairés.
10. Vol, caméra et inclinaison : pas de sections qui disparaissent, de pixels blancs, de gels de découpe ou de spam DS.
11. Neige, feuilles, flaques, lune de sang si ces mods sont installés.
12. Plusieurs joueurs, serveur dédié, puis deux sauvegardes ouvertes successivement sans relancer Minecraft.
13. Mettre à jour le tableau des versions avec les résultats réellement observés et les cas restant à tester.
