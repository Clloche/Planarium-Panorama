# Maintenance — Planarium Panorama

Document de développement, pas pour les joueurs (le README reste centré sur les fonctionnalités).
Il liste, pour chaque intégration : la version testée, ce qui dépend de détails internes d'un autre mod,
comment ça casse si ce mod change, et les tests à refaire après une mise à jour.

## Versions testées et références retrouvées

Relevé du 2026-10-21, à partir du README, des JAR locaux et des journaux conservés.
La source distingue une version documentée dans le README, une version effectivement chargée et un JAR présent
mais désactivé. Ce relevé complète l'historique ; aucun nouveau test en jeu n'a été effectué pour le rédiger.
La date ci-dessous est celle de la source ou du relevé du JAR ; « — » signifie que la date du test n'est pas conservée.

| Mod | Version de référence | Source / état | Date de la source |
|---|---|---|---|
| Minecraft / NeoForge | 1.21.1 / 21.1.228 | Dernières sessions locales | 2026-10-21 |
| Ars Nouveau | 5.13.3 | Dernières sessions locales | 2026-10-21 |
| Sable | 2.0.4 | Version déclarée dans les dernières sessions ; JAR 2.0.6 présent, à vérifier séparément | 2026-10-21 |
| Sable Companion | 1.6.0 | Dernières sessions locales ; dépendance embarquée de Sable | 2026-10-21 |
| Ars Sable | 1.2.0 | Dernières sessions locales | 2026-10-21 |
| Create | 6.0.10 | README et dernières sessions locales | 2026-10-21 |
| Flywheel | 1.0.6 | Dernières sessions locales ; dépendance embarquée de Create | 2026-10-21 |
| Create Aeronautics / Simulated | 1.3.2 | Dernières sessions locales ; bundle Aeronautics | 2026-10-21 |
| Contraption Lights | 1.5.2 ; 1.5.4 dans des sessions antérieures | 1.5.2 chargé le 21 ; 1.5.4 chargé dans le log du 17 | 2026-10-21 / 2026-10-17 |
| Dynamic Surroundings | 0.4.6 | Dernières sessions locales | 2026-10-21 |
| Enhanced Celestials 2 (Core) | 2.0.3.3 | Dernières sessions locales | 2026-10-21 |
| Enhanced Celestials 2 (Default Lunar Events) | 2.0.1.3 | Dernières sessions locales | 2026-10-21 |
| Puddles & Floods | 1.1.6 | Dernières sessions locales | 2026-10-21 |
| YetAnotherConfigLib | 3.8.2 | Dernières sessions locales | 2026-10-21 |
| Better Clouds | 1.13.11 | Dernières sessions locales | 2026-10-21 |
| AmbientSounds | 6.3.10 | JAR local actuellement désactivé (`.jar.disabled`) | 2026-10-21 |
| Falling Leaves | 2.6.0 | README et JAR de test ; ancien log : 2.5.1 | — |
| Snow! Real Magic | 12.2.3+neoforge | Version chargée dans un ancien log | 2026-10-17 |
| Serene Seasons | 10.1.0.3 | Version testée indiquée dans le README | — |
| Alex's Caves | 2.0.2 | Liste des mods d'un ancien rapport de plantage | 2026-10-15 |
| Distant Horizons | 3.3.3 | Version testée indiquée dans le README | — |
| Traveler's Titles | 5.1.3 | Version testée indiquée dans le README | — |
| Voxy (NeoVoxy) | 0.5.0-alpha.12 | README et dernières sessions locales | 2026-10-21 |
| Simple Clouds | Non relevée | Aucun JAR ni numéro de version retrouvé dans les sources consultées | — |
| Windy: Configurable | 1.2.1 | README ; ancien log : 1.2.0 | — |
| Swinging Lanterns | 2.0.4 | Version testée indiquée dans le README | — |
| Sodium | 0.8.13 | README et dernières sessions locales | 2026-10-21 |
| Jaden's Nether Expansion | 2.4.1 | Version testée indiquée dans le README | — |
| AmbientEnvironment | 18.0.0.2 | Session de test ; variation d'herbe en damier constatée en vol | 2026-10-21 |

Sources locales utilisées : `README.md`, `run/mods`, les métadonnées des dépendances embarquées,
`run/logs/latest.log`, `build/sublevel-repro/logs/latest.log`, `run/logs/2026-10-17-12.log.gz`
et `run/crash-reports/crash-2026-10-15_14.20.22-client.txt`.
Les chemins dans `run` et `build` servent de provenance locale et peuvent disparaître lors d'un nettoyage.
Pour une prochaine mise à jour, relever la version effectivement chargée dans le log en plus du nom du JAR.

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
- La plupart des injections sont en `require = 0` (`PlanariumRendererMixin` : 10, `PlanariumRenderingWorldMixin` : 3,
  `PlanariumProjectorRendererMixin` : 2, `StructureRenderDataMixin` : 1). Quelques-unes sont obligatoires, notamment
  `PlanariumTileSubLevelMixin` et l'injection sur `tickRateManager` de `PlanariumRenderingWorldMixin`.
  Une mise à jour peut donc **planter** (injections obligatoires) **ou désactiver en silence** (les autres).
- Constantes recopiées à la main dans `ClientView` : `PAD`, `ARS_SCALE`, `ARS_OFFSET`, `GIANT`, `Y_SHIFT = 25`
  (échelle et position du rendu d'Ars). Si Ars les change, rien ne plante : le décor est seulement décalé.
- Format de la dimension de poche (bocal de 32 × 32, parois `ars_nouveau:dim_boundary`, `PlanariumChunkGenerator.innerBox`).

Tests :
- Décor géant aligné contre les parois et sous le plancher (pas de trou ni de chevauchement au bord).
- Miniature : lumière réelle (pas 15 partout), ombrage des coins normal, parois en verre complètes.
- Parois et plancher de l'intérieur éclairés (`ProjectorLighting`), plateau de la miniature éclairé par un feu dans le bocal.
- Planarium sur un vaisseau Sable : le bocal trouve son Planarium.

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

### Ars Sable

Dépend de (réflexion, `ArsSableCompat`) : `SublevelPosData.from`, `getForPlayer`, `Entry.trackedPos`, `Entry.sublevelId`.
Casse : avertissement, et le suivi du vaisseau après reconnexion se rabat sur la recherche normale.
Tests : les mêmes reconnexions que Sable.

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

### Create Aeronautics

Dépend de : noms de classes (`HotAirBurnerBlockEntity`, `BasePropellerBlockEntity`, `SmartPropellerBlockEntity`),
champ public `prop`, méthodes `isActive()`, `spawnParticles()` (`CopyParticles`), son du brûleur (`MachineSounds`).
Casse : particules ou sons absents en silence.
Tests : brûleur (flamme, son), hélices (rotation, particules) dans les copies.

### Contraption Lights

Dépend de : `ColorLightEngine.colorSection(Scratch, BlockAndTintGetter, int, int, int, boolean)` (signature exacte,
`ClColorSectionMixin`), `ColorLightEngine.invalidate`, `clear`, format des couleurs dans la lumière (`ClTag`).
Casse : la redirection des couleurs du décor est désactivée avec un avertissement (plus de repli sur `setSource`).
Tests : torche colorée dans le décor géant (pas de clignotement blanc), dans la miniature, et dans le bocal près des parois.

### Dynamic Surroundings (le plus de points d'accroche)

Dépend de : nombreux mixins `Ds…` (scanners de biome et de plafond, variables de biome et météo, saisons, sons bloqués,
lucioles, dimensions) et champs privés lus par nom : `dimensionRules`, `configuredCloudHeight` (`DsAltitude`),
`obstacles`, `TAG_LIBRARY` (`DsFirefliesCompat`), `growthRate` (`DsRipplesCompat`).
Casse : surtout en silence.
Tests : ambiance du biome du dehors dans le bocal, silence sous abri, sons de grotte sous terre, lucioles dans le décor,
cascades du décor, pluie du dehors.

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

## Règles générales

- **Fils d'exécution** : la comparaison des photos (`ClientView`, `DIFF_THREAD`), l'instantané de lumière lu par les
  fils de Sable (`SubLevelLight`) et les couleurs du bocal (`JarColor`, `WallColors`) travaillent sur des copies figées.
  Sur ces fils, ne jamais lire le monde ou le moteur de lumière directement.
- **Fin d'un monde** : tout état statique côté serveur doit être vidé à l'arrêt du serveur (`onServerStopping`),
  sinon il passe au monde suivant en solo. Fait : cache de l'anneau lointain, états de l'anneau et du vol.
  À faire : `RELIT`, `FLOOR_CLEANED`, `MINI_LIGHT`, `MINI_ENTITIES`, `MINI_ENTITIES_DUE`, `MINI_ENTITIES_NEXT`.
- **Messages du client** (`ViewTogglePayload`, `SoundPrefsPayload`, `SubLevelPrefsPayload`) : les valeurs identiques
  sont déjà filtrées par endroits, mais des bascules ON/OFF répétées forcent des captures complètes ou des changements
  de tickets. Sur un serveur public : limiter à un changement par seconde par joueur.
- **Tests du dépôt** : `src/test` n'est plus suivi par Git ; `checkSubLevels` est sauté s'il est absent. Garder les
  tests en local et les lancer avant une publication.

## Après une mise à jour du modpack (liste courte)

1. Lancer le jeu et lire le log : support Sable activé ; aucun avertissement « … indisponible / unsupported ».
2. Décor géant aligné avec les parois (Ars Nouveau).
3. Coffre et engrenage éclairés dans le bocal (Flywheel).
4. Torche colorée dans le décor (Contraption Lights).
5. Sons du biome du dehors (Dynamic Surroundings, AmbientSounds).
6. Machine de Create et hélice d'Aeronautics animées dans le décor.
7. Vaisseau : déplacement, arrêt, sortie, déconnexion/reconnexion (Sable, Ars Sable).
8. Neige, feuilles, flaques, lune de sang si ces mods sont installés.
9. Mettre à jour le tableau des versions testées.
