# Maintenance — Planarium Panorama

Document de développement, pas pour les joueurs (le README reste centré sur les fonctionnalités).
Il liste, pour chaque intégration, ce qui dépend de détails internes d'un autre mod, comment ça casse si ce mod
change, et les tests à refaire après une mise à jour.

État du code : **1.8-dev** (Minecraft 1.21.1, NeoForge 21.1.228).

## Versions de référence

Dernières versions connues pour fonctionner. Relever la version réellement chargée dans le log, pas seulement le
nom du JAR, et mettre ce tableau à jour après chaque test.

| Mod | Version |
|---|---|
| Ars Nouveau | 5.13.3 |
| Sable / Sable Companion | 2.0.6 / 1.6.0 |
| Ars Sable | 1.2.0 |
| Create / Flywheel | 6.0.10 / 1.0.6 |
| Create Aeronautics / Simulated / Offroad | 1.3.2 |
| Contraption Lights (+ Compat 2.4.0, Fixes 1.5.0) | 1.5.2 |
| Dynamic Surroundings | 0.4.6 et 0.4.7 |
| Enhanced Celestials 2 (Core / Default Lunar Events) | 2.0.3.3 / 2.0.1.3 |
| Puddles & Floods | 1.1.6 |
| YetAnotherConfigLib | 3.8.2 |
| Better Clouds | 1.13.11 |
| Sodium | 0.8.13 |
| AmbientEnvironment | 18.0.0.2 |
| Lithium | 0.15.4 |
| AmbientSounds | 6.3.10 (pas retesté récemment) |
| Falling Leaves | 2.6.0 |
| Snow! Real Magic | 12.2.3 |
| Serene Seasons | 10.1.0.3 |
| Alex's Caves | 2.0.2 |
| Distant Horizons | 3.3.3 |
| Traveler's Titles | 5.1.3 |
| Voxy (NeoVoxy) | 0.5.0-alpha.12 |
| Windy: Configurable | 1.2.1 |
| Swinging Lanterns | 2.0.4 |
| Jaden's Nether Expansion | 2.4.1 |
| Simple Clouds | non relevée |

## Comment une intégration peut casser

Quatre cas, du plus visible au plus dangereux :

1. **Plantage au démarrage** : mixin obligatoire dont la cible a disparu. Message clair, facile à voir.
2. **Désactivation avec avertissement** : la réflexion échoue, un avertissement est écrit une fois dans le log.
3. **Désactivation silencieuse** : mixin en `require = 0` qui ne s'applique plus, ou réflexion qui échoue sans log
   (ou seulement en DEBUG). Le bilan des mixins couvre les mixins ; la réflexion demande encore un test en jeu.
4. **Écriture en mémoire native** (Flywheel) : un mauvais calcul n'est pas une exception récupérable.
   Affichage corrompu ou plantage possible.

### Bilan des mixins

Package `diagnostics` : `PlanariumMixinPlugin` fait l'inventaire des crochets en lisant nos mixins avant le chargement
des cibles ; `MixinHealthExtension` vérifie chaque classe finale (après MixinExtras) ; `MixinHealthReport` écrit
au premier monde ouvert une ligne par mod :

```
[Planarium Panorama] Mixins <mod> <version> : <ok>/<attendus> verifies
```

- Une ligne en WARN avec « inactifs : … » nomme les crochets qui ne s'appliquent plus.
- « pas encore charges » : la cible n'a pas encore été chargée (normal pour certaines classes), ce n'est pas un échec.
- `@ExpectIn` limite un crochet multi-cibles à certaines cibles ; `@Alternative` groupe les variantes d'un même
  crochet selon la version d'un mod (une seule doit s'appliquer).
- `-Dplanariumview.strictMixins=true` (développement) : un échec confirmé arrête le jeu.
- Nouveau mixin : vérifier qu'il apparaît dans le bilan, et ajouter `@ExpectIn` / `@Alternative` au besoin plutôt
  que de laisser un faux échec.

### Avertissements : règles

- Avertir seulement quand **le mod est installé mais incompatible**. Un mod absent ou une intégration désactivée
  volontairement (option sur OFF) ne doit rien écrire.
- Les diagnostics détaillés vont en `LOGGER.debug`.

## Par intégration

### Ars Nouveau (dépendance obligatoire, la plus exposée)

Dépend de :

- Mixins sur des classes internes : `PlanariumRenderer`, `PlanariumRenderer.StructureRenderData`,
  `PlanariumRenderingWorld`, `PlanariumProjectorRenderer`, `PlanariumTile`, `DimBoundary`.
  Certaines injections sont obligatoires (`PlanariumTileSubLevelMixin`, `tickRateManager`, les deux surcharges de
  `getEntities` dans `PlanariumRenderingWorldMixin`) : une mise à jour peut **planter** ou **désactiver en silence**.
- Constantes recopiées à la main dans `ClientView` : `PAD`, `ARS_SCALE`, `ARS_OFFSET`, `GIANT`, `Y_SHIFT = 25`.
  Si Ars les change, rien ne plante : le décor est seulement décalé.
- Format de la dimension de poche (bocal de 32 × 32, parois `ars_nouveau:dim_boundary`, `PlanariumChunkGenerator.innerBox`).
- Contrat du faux monde : les recherches d'entités doivent renvoyer une liste vide, pas `null` (Ars 5.13.3 renvoie
  `null` ; le mixin corrige ces retours dans les copies). Ne pas les rediriger vers le vrai monde : les positions
  appartiennent au repère de la copie.
- `ArsParticles` : effets des block entities d'Ars (lumières, appliques, Whirlisprig, chambre d'imprégnation) dans
  la miniature et le décor détaillé, capturés une fois puis rejoués.
- `ProjectorLighting` : remplace le `MultiBufferSource` de `PlanariumProjectorRenderer` (modèle cutout) pour éclairer
  parois et plancher bloc par bloc. Suppose un seul projecteur dessiné par image.

Tests :

- Décor géant aligné contre les parois et sous le plancher (pas de trou ni de chevauchement au bord).
- Miniature : lumière réelle (pas 15 partout), ombrage des coins normal, parois en verre complètes.
- Parois et plancher de l'intérieur éclairés, plateau de la miniature éclairé par un feu dans le bocal.
- Planarium sur un vaisseau Sable : le bocal trouve son Planarium.
- Plusieurs Planariums vers le même bocal : pas de reconstruction de la miniature chaque seconde.

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

- Géométrie et poses voyagent séparément. Poses toutes les 2 ticks, interpolées sur 100 ms côté client. Matrices en
  double précision (`SubLevelTransforms`) pour les plots éloignés.
- Le déplacement seul ne marque pas les sections comme modifiées. Après 20 ticks de stabilité, leur lumière est
  reprise si leur pose a changé. Les vrais changements de blocs suivent `blockUpdateSeconds` et `refreshSeconds`.
- Recaptures différées : budget partagé de 2 ms par appel, au moins une autorisée ; les sections nouvelles passent
  immédiatement.
- La liste des sections visibles précède les paquets de géométrie. Garder les renvois après connexion/changement de
  dimension et la limite de 256 sections par scène.
- Paquets NBT compressés : 1 000 000 octets maximum sur le réseau, 32 Mio décompressés. Les champs facultatifs de
  pose (dont `fluids`) doivent garder un repli.
- `SubLevelView.rainShelter` : les blocs des structures abritent de la pluie dans `WeatherView`.
- Les particules du décor (`SceneryHooks.sceneryFrame`, `FramedParticle`) suivent le repère incliné de la structure.

Tests :

- Vaisseau visible dans le décor géant et la miniature, qui bouge et tourne (90°, 180°, 45°).
- Planarium incliné : soleil, lune, étoiles et nuages alignés avec le décor (lacet, tangage, roulis).
- Planarium immergé par le mouvement du vaisseau : brouillard d'eau ou de lave, ambiance sous-marine, retour à l'air.
- Découpe du décor contre le bocal (`ClippedVertexBuffer`, `JarClip`) : liquide traversant une paroi avec un roulis
  de 70°, puis tangage et déplacement ; passage au décor en vol et retour.
- Déplacement (décor en vol), arrêt (photo normale puis anneau 5 s plus tard), sortie pendant le mouvement, puis
  déconnexion/reconnexion à l'intérieur.
- Vaisseau à l'intérieur du bocal : éclairé comme le bocal (`SubLevelLight`).
- Déplacement prolongé : pas de recapture complète à chaque pose, ni saccade avec coupure des sons.
- Pluie : pas de pluie sous un toit du vaisseau ; particules de blocs inclinées avec le vaisseau.
- Block entities du vaisseau (moteur, volant, hélice) : même brouillard que les blocs voisins, y compris sous l'eau.

### Ars Sable

Dépend de (réflexion, `ArsSableCompat`) : `SublevelPosData.from`, `getForPlayer`, `Entry.trackedPos`, `Entry.sublevelId`.
Casse : avertissement, et le suivi du vaisseau après reconnexion se rabat sur la recherche normale.
Tests : les mêmes reconnexions que Sable.

### Planariums voisins (Ars Nouveau)

`NeighborPocketsServer`, `NeighborPocketsPayload`, `NeighborPrefsPayload`, `NeighborPockets`, `NeighborPlanariumModel`.
Photo figée de la poche voisine (pas de simulation). Dépend de `PlanariumTile.key`, du format des poches, du modèle
du Planarium et de `VisibleCapture`.

- Cylindre du rayon détaillé, tri par proximité, au plus 10 voisins (option client, 4 par défaut, transmise au serveur).
- Cache serveur LRU de 64 photos par dimension de poche, invalidé par les changements de blocs. Pas de mise à jour
  en direct d'une photo déjà affichée.
- Sur Sable, la recherche concerne les Planariums de la même structure.
- Parois partagées masquées ; ciel et lumière extérieure relus chaque seconde, la table `JarLight.computeTable`
  n'est recalculée que si les mesures changent. Les anciens buffers restent affichés jusqu'au remplacement.

Tests : bocaux adjacents et en diagonale, hauteurs différentes, torche dans une poche, options 0/1/4/10 pendant la
visite, plusieurs joueurs. Pas de verre noir, de mur opaque entre bocaux adjacents ni de doublon de coque.
Plafond ouvert puis fermé au-dessus d'un voisin, lampe posée puis retirée à côté.

### Flywheel (risque le plus grave)

Dépend de : `LightStorage.SOLID_SIZE_BYTES` et du **format interne** des données de lumière (18 × 18 × 18 octets par
section, blocs dans les 4 bits bas, ciel dans les 4 hauts, après le bloc des blocs pleins), via
`FwLightDataCollectorMixin` → `FlywheelCompat.boostSection` qui écrit directement en mémoire (`MemoryUtil`).
Aussi : `VisualizationManager.get/blockEntities`, `queueRemove/queueAdd`, `onLightUpdate`.

Casse : si le format change, l'écriture tombe au mauvais endroit. **Pas d'exception récupérable.**

À faire : vérifier `missing` au début de `boostSection` ; noter ici la version dont le format a été vérifié.

Tests (obligatoires à chaque mise à jour de Flywheel ou de Create) : coffre, lit, engrenage dans le bocal près
d'une torche et d'une paroi éclairée ; même lumière que les blocs autour, qui suit le jour/nuit.

### Create

Dépend de : `SmartBlockEntity.markVirtual()` et `tick()` (mode virtuel de Ponder) dans `CreateCopies` ;
`SoundScapes` (`CreateSoundScapesMixin`). Le mode virtuel a déjà surpris (sifflets alimentés sans redstone).

Les copies restent virtuelles et attachées au faux monde : ne jamais réattacher ni faire tourner la block entity
réelle. `CopyParticles` s'occupe des effets, `MachineSounds` des sons ; l'animation continue sans eux.

Contraptions assemblées sur Sable (`ContraptionCapture`, réflexion sur `AbstractContraptionEntity.getContraption()`,
`Contraption.getBlocks()`, `toGlobalVector`) :

- Sections capturées dans le repère local de la contraption ; une signature des états/NBT déclenche la recapture.
- Origines factices réservées par contraption (`y = -1 000 000`, marge de 64 blocs en x) : les garder distinctes.
- Champs `group`, `group_pose`, `local` : une seule rotation interpolée par contraption. Revenir à une interpolation
  par section refait apparaître le « wobble » des voiles.
- Les tests de proximité `origin` et `worldBounds` de `SubLevelServer.dirty()` sont indépendants (les sections de
  contraption ont `origin = null`).
- Casse : `Unsupported Create API; contraptions on sub-levels are not shown`.

Tests : machines animées et leurs sons dans le décor et la miniature, sifflet non alimenté silencieux ;
hélice assemblée/désassemblée, voile sur plusieurs sections pendant rotation et inclinaison, bloc assemblé modifié,
lampe posée près d'une contraption immobile (sa lumière doit suivre sans réassembler).

### Create Aeronautics / Simulated

Dépend de : `HotAirBurnerBlockEntity`, `BasePropellerBlockEntity`, `SmartPropellerBlockEntity`, champ `prop`,
`isActive()`, `spawnParticles()`, son du brûleur, `HotAirEmberParticleData(boolean)`. Le rendu immédiat du brûleur
change l'état OpenGL : `CreateCopies.render` restaure shader, profondeur et culling.

Portable engine (`PortableEngineBlockEntity`) : son `tick()` client anime `visualSpeed`, `visualStrength` et la
trappe (état lu dans `BurnTime`, `GeneratedSpeed`, `SuperHeated`). En mode virtuel, `spawnParticles()` est appelé
par `CopyParticles` si `getCurrentBurnTime() > 0`. La copie ne consomme pas de carburant.

Casse : `Burner / Propeller / Portable engine preview particles unavailable`, ou `Create preview animation skipped`.
Tests : brûleur (flamme, son), hélices, moteur éteint/allumé/surchauffé dans le décor et la miniature, avec particules
puis sons désactivés séparément (l'animation doit rester).

### Contraption Lights

Dépend de : `ColorLightEngine.colorSection(Scratch, BlockAndTintGetter, int, int, int, boolean)` (`ClColorSectionMixin`),
`ColorLightEngine.invalidate`, `clear`, `ColorLightFeature.tag`, `tagBaked`, `baking`, `blendCorner`, et du format
des couleurs dans la lumière (`ClTag`). `ClColorFeatureMixin` intercepte aussi `tag(…, boolean)`.
Casse : redirection des couleurs désactivée avec avertissement.

- Compatibilité automatique si le mod est installé. `ColorCompat` garde un contexte par thread et un cache par faux
  monde ; ne pas vider le cache global à chaque construction ni utiliser `setSource`.
- Sources portées (`JarWalls.findSources`) : `WrappedBlockLight.lightState` par réflexion, puis repli Moonlight
  `IBlockHolder.getHeldBlock()` (lanternes murales d'Amendments).
- Le **terrain en vol** utilise volontairement `beginWithoutColor()` : l'absence de couleur y est normale.

Tests : torche colorée dans le décor (pas de clignotement blanc), la miniature et près des parois ; lanterne murale
et lanterne des âmes ; source retirée sans ancienne teinte ; entrée en vol puis retour sans saccade.

### Dynamic Surroundings (le plus de points d'accroche)

Dépend de : nombreux mixins `Ds…` (scanners de biome et de plafond, variables de biome et météo, saisons, sons bloqués,
lucioles, dimensions, réverbération `DsSoundFxMixin`) et champs privés lus par nom : `dimensionRules`,
`configuredCloudHeight` (`DsAltitude`), `obstacles`, `TAG_LIBRARY` (`DsFirefliesCompat`), `growthRate` (`DsRipplesCompat`).

- `DsFogCalculatorMixin` (`HolisticFogRangeCalculator.render`) garde le brouillard liquide du bocal immergé.
- `DsBiomeScannerMixin` ne doit pas relancer ses recherches sur un faux monde à chaque lecture de biome.
- 0.4.7 : `DsPointLightsMixin` adapte le rayon des lucioles copiées dans `PointLights.add` (le chemin
  `FireflyLights` reste pour 0.4.6) ; `DsWeatherSimulationMixin` fournit la pluie synchronisée à `WeatherSimulation`
  et au vent ; `DsDimensionInfoMixin` force `particleWeather()` à faux dans les poches.
- Les nouveaux effets 0.4.7 (pluie sur les vitres, givre, brouillard au sol) ne sont pas projetés dans le décor.

Tests : ambiance du biome du dehors, silence sous abri, sons de grotte, lucioles aux deux échelles, cascades, pluie et
orage puis retour au sec, vent, réverbération des sons du dehors, immersion eau/lave, pas de spam de brouillard,
pas de pluie DS à taille normale dans une poche sans pluie réelle.

### AmbientSounds

Dépend de : mixins `As…` (moteur, environnement, terrain, biome) et un champ privé (`AmbientSoundsCompat`).
Tests : sons du biome et du terrain du dehors dans le bocal.

### Falling Leaves, Snow! Real Magic

Champs privés : `SpriteContents.byMipLevel` (Falling Leaves), `variantModel`, `wrapped` (Snow! Real Magic). Casse en silence.
Tests : feuilles qui tombent dans le décor et la miniature ; neige sur un bloc dans le décor.

### Enhanced Celestials 2, Serene Seasons, Puddles & Floods, Alex's Caves, Distant Horizons, Traveler's Titles, YACL

Mixins ciblés (`EcForecastMixin`, `DsSereneSeasonsMixin`, `PfPlacementMixin`, `AcAmbersolLightMixin`,
`DhClientApiMixin`, `TravelersTitlesMixin`, `Yacl…`) en `require = 0`.
Tests rapides : lune de sang du dehors ; couleurs de saison ; flaques dans le décor ; pas de lumière fantôme sous
l'ambersol ; pas de bande DH autour du bocal ; pas de « ??? » en entrant ; menu de config (groupes, notes « X est
désactivé », valeurs grisées, sauvegarde immédiate, Échap).

### Sodium

Le chemin rapide de Sodium pour les modèles s'applique aux copies de mobs, mais pas son culling ni celui
d'EntityCulling : les copies sont dessinées par nous (voir l'occlusion plus bas).

## Mobs et véhicules

### Décor géant (`OutsideMobsServer`, `OutsideMobs`, `OutsideMobsPayload`, `ViewPrefsPayload`)

- Le serveur choisit jusqu'à `outsideMobs` mobs (0–64, option client bornée par le serveur) dans le rayon détaillé ;
  les mobs affichés gardent leur place, les plus proches remplissent les places libres. Bateaux, radeaux et
  wagonnets sont affichés dès que le monde fait vivre le dehors (`keepOutsideRunning`), hors limite des mobs ;
  les mobs à bord demandent une limite au-dessus de 0.
- Mobs ou véhicules affichés gardent le rayon détaillé + `SIMULATION_MARGIN` actifs (`ViewTickets`), même avec
  les sons du monde sur OFF.
- Les copies ne tickent pas : poses, animations (`MobAnimations`, `MobAnimationTimeline`), dégâts, attaque, mort,
  effets de potion, feu, ombres (`MobShadows`), particules et sons sont reçus du serveur ou rejoués.
- Mixins : `MobAnimationBroadcastMixin` (événements envoyés par `ServerChunkCache`), `RandomStrollGoalMixin` et
  `SquidRandomMovementGoalMixin` (les mobs observés continuent de bouger sans joueur proche), `SquidInkMixin`,
  `EntityEnvironmentMixin` (les copies ne lisent jamais l'eau ou le sol de la poche), `LivingEffectParticlesMixin`,
  `BoatSceneryMixin`, accesseurs (`SynchedEntityDataAccessor` lit l'apparence sans consommer les changements
  destinés aux vrais clients).
- `SceneryMobOcclusion` / `VoxelOcclusion` : occlusion des mobs du décor par ses blocs, seulement au rendu
  (aucun effet sur les ticks, sons ou paquets). Désactivée en vol et tant que des sections sont en attente.
  Mesure (Spark, 60 s) : derrière un mur, le décor passe d'environ 7,8 % à 4,1 % du fil de rendu ; à découvert,
  le test coûte environ 0,2 %. Les ombres des mobs cachés restent dessinées (l'ombre peut dépasser du mur).

Tests : mobs qui apparaissent après quelques secondes, se déplacent, nagent, volent, montent un autre mob ou un
bateau ; coup, mort, potion, feu ; sons des mobs avec les sons du monde OFF ; limite changée pendant la visite ;
mob caché derrière un mur puis visible par une porte ou un trou d'un bloc, sans clignotement en tournant vite.

### Miniatures (`MiniatureSync`, `MiniLive`, `MiniEntities`, `MiniEntityTypes`, paquets `Mini…`)

- Sans direct : photo des mobs de la poche (capturée aussi au déchargement, ticket de lecture de niveau 33),
  affichée avec de petites animations. Sélection stable : ordre `MINI_ORDER`, puis nouveaux mobs un type à la fois
  (`varied`, types les moins présents d'abord, UUID dans un type).
- Direct (`keepMiniaturesRunning` du monde + option client) : la miniature la plus proche regardée (8 blocs) reçoit
  ses mobs 5 fois par seconde ; tickets de niveau 31 sur les 4 chunks, au plus `maxRunningMiniatures` Planariums
  sur le serveur. Les copies d'un même Planarium partagent le direct.
- Véhicules : affichés dès que le monde fait vivre les miniatures, jusqu'à 64, hors limite des mobs.

Tests : 20 mobs de deux types avec une limite de 8 (variété), mob qui naît/meurt pendant qu'on regarde, sortie de
portée et retour, plusieurs joueurs sur le même Planarium, limite atteinte de `maxRunningMiniatures`, bateau avec
passager, permission du monde OFF (tout caché, choix du joueur conservés).

## Rendu, captures et performances

- **Forme du détail** : `DetailShape` définit le cylindre commun aux captures et au rendu. Tester les diagonales et
  la frontière détail/anneau/vol.
- **Précision des sommets** : `GridSnap.snapMesh` (grille de 1/4096) avant tri et upload ; les faces non translucides
  débordent de 1/8192 de bloc géant pour fermer les jonctions en T.
- **Parois et plancher** : `ProjectorLighting` garde tous les points du contour des rectangles fusionnés et arrondit
  au 1/1024 de bloc. Coût mesuré : environ 5 % du fil de rendu, dont 4 % pour `Subdivider.replay`, qui renvoie à
  chaque image les sommets en cache. Piste : les garder dans un `VertexBuffer` (une seule reconstruction par image
  au plus, ancien rendu gardé pour les shaders, les fissures de cassage et les modèles translucides).
- **Culling** : `SceneryFrustum` traite le décor détaillé, l'anneau et le vol. Une boîte inconnue reste visible.
- **Découpe** : `ClippedVertexBuffer` garde une copie CPU des sommets ; `glGetBufferSubData` reste un repli.
- **Anneau asynchrone** : `FarRing` prépare sur son exécuteur, installe sur le fil de rendu ; `loadVersion` rejette
  les résultats obsolètes. Ne pas lire un monde vivant depuis l'exécuteur.
- **Cache Minecraft** : le cache de lumière de `ModelBlockRenderer` est vidé (`clearCache`, dans un `finally`) après
  chaque construction d'anneau, de vol ou de voisins.
- **Budgets** : `CaptureBudget` partage 6 ms par tick serveur ; le client construit anneau et voisins (2 ms par image
  chacun) et vol (2,5 ms). Une section individuelle peut dépasser ce budget.
- **Miniatures de structures en mouvement** : `Part.mini()` peut reconstruire le maillage toutes les 20 ticks.

Diagnostic des sections lentes : `FarRing` journalise les constructions de plus de 20 ms ;
`-Dplanariumview.diagSections=true` donne les coûts par type de bloc.

Tests de rendu : tourner vite la caméra, changer le FOV, incliner le vaisseau, longer une paroi avec eau/verre,
entrer/sortir, démarrer/arrêter le vol. Pas de disparition au bord de l'écran, de fissures blanches, de gel au
premier découpage ni de vieille photo après un changement de monde. Avec et sans Sodium.

## Règles générales

- **Structure** : `ClientView` et `ServerView` ne font plus que coordonner. Côté client : `SceneryState`,
  `SceneryReceiver`, `SceneryPatcher`, `SceneryMeshes`, `SceneryRenderer`, `SceneryFog`, `SceneryHooks`,
  `ViewPrefsSender`. Côté serveur : `SceneryCapture`, `TrackUpdater`, `ViewTickets`, `PlanariumAnchor`,
  `OutsideSounds`, `PocketLighting`, `MiniatureSync`.
- **Fils d'exécution** : la comparaison des photos (`DIFF_THREAD`), l'instantané de lumière de Sable (`SubLevelLight`)
  et les couleurs du bocal (`JarColor`, `WallColors`) travaillent sur des copies figées. Jamais de lecture du monde
  depuis ces fils.
- **Fin d'un monde** : tout état statique serveur doit être vidé à l'arrêt (`ServerView.onServerStopping` /
  `onServerStopped`), sinon il passe au monde suivant en solo. À faire : `RELIT` et `FLOOR_CLEANED`
  (`PocketLighting`).
- **Caméra des copies** : `CreateCopies.clear()` remet `cameraProxy` à `null` après déconnexion.
- **Messages du client** (`ViewTogglePayload`, `SoundPrefsPayload`, `SubLevelPrefsPayload`, `NeighborPrefsPayload`,
  `ViewPrefsPayload`, `MiniWatchPayload`) : des bascules répétées forcent captures ou changements de tickets. Sur un
  serveur public : limiter à un changement par seconde par joueur. Le serveur borne toujours les limites reçues.
- **Protocole et serveur dédié** : garder les canaux facultatifs et `hasChannel` ; pas de classe cliente dans les
  captures serveur. Tester une connexion multijoueur et un serveur dédié après modification des paquets.
- **Vérifications Gradle** (`src/animationChecks`, suivies par Git, lancées par `check`) : `checkOutsideMobAnimations`,
  `checkOutsideMobIntegration`, `checkMiniatureLive`, `checkSceneryOcclusion`. `checkSubLevels` dépend de
  `src/test`, non suivi, et est sauté s'il est absent.

## Après une mise à jour du modpack (liste courte)

1. Lancer le jeu et lire le log : bilan des mixins sans « inactifs », support Sable activé, aucun « unsupported ».
2. Décor géant aligné avec les parois (Ars Nouveau).
3. Coffre et engrenage éclairés dans le bocal (Flywheel).
4. Torche colorée dans le décor (Contraption Lights).
5. Sons du biome du dehors (Dynamic Surroundings, AmbientSounds).
6. Machine de Create, brûleur, hélice et portable engine dans le décor et la miniature.
7. Voile assemblée sur plusieurs sections : rotation sans « wobble ».
8. Vaisseau : déplacement, arrêt, pluie sous abri, sortie, déconnexion/reconnexion (Sable, Ars Sable).
9. Planariums voisins : limite respectée, verre partagé masqué, planchers éclairés.
10. Mobs et véhicules du dehors, puis miniature avec et sans direct.
11. Vol, caméra et inclinaison : pas de sections qui disparaissent, de pixels blancs ni de spam DS.
12. Neige, feuilles, flaques, lune de sang si ces mods sont installés.
13. Plusieurs joueurs, serveur dédié, puis deux sauvegardes ouvertes successivement sans relancer Minecraft.
14. Mettre à jour le tableau des versions.
