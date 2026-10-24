package com.holzyn.actor.domain.mapgen.algo;

import com.holzyn.actor.domain.mapgen.model.AssetSemantics;
import com.holzyn.actor.domain.mapgen.model.WorldMapBrief;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 落位器（S4）：把「建筑类型库 + 路网 + 地形 + 世界契约」变成一张真正像聚落的图。
 *
 * <p><b>它要解决的问题</b>：随机撒点只能得到「一堆乱七八糟的盒子」。真实聚落的秩序来自几条硬约束：</p>
 * <ul>
 *   <li><b>占用场</b>：水面/河道/道路（按路宽）/广场先占位，后面谁都不能压上去；</li>
 *   <li><b>贴街</b>：房子的正面朝向 = 路的法线——于是整条街的房子天然对齐、正面朝街；</li>
 *   <li><b>按地段选类型</b>：核心区出商铺/公共，环带出民居，外围出厂坊/生产；</li>
 *   <li><b>院落</b>：民居按概率成院（正屋 + 厢房 + 后罩棚），院落登记成组，供 LOD 与命名用；</li>
 *   <li><b>生产与地标</b>：农田成片（按户切条田）、菜畦贴着自家院子、林地成团、
 *       码头伸入水面、桥架在过河处、广场铺在核心；</li>
 *   <li><b>契约优先</b>：世界数据里点名的设施/住所先落位（地标占最好的地），剩下的才随机铺。</li>
 * </ul>
 *
 * <p><b>坐标与单位</b>：全部是米（原点在西南角，X 向东、Z 向南）；1 格 = {@code metersPerCell} 米。
 * 本类只产出「要素/地块/组 + 语义 JSON」，落库与渲染由 S5 负责。</p>
 *
 * <p><b>确定性</b>：全程用 {@link Rng}（SplitMix64）——同种子同结果，用户「重掷」只换种子。</p>
 *
 * <p><b>语义 JSON 是上下游的唯一契约</b>：{@code homeRef}/{@code owner}/{@code anchor}/{@code region}/
 * {@code flags} 都写在 {@code semanticJson} 里，持久化层解析成列、日程层用来定点、前端用来上色。</p>
 */
public final class MapPlacer {
   /** 临街地块之间的间隙（米）：留出山墙与排水沟。 */
   private static final double PARCEL_GAP = 1.6;
   /** 建筑退线（米）：房子不能贴着路缘。 */
   private static final double SETBACK = 1.4;
   private final TerrainData t;
   private final TerrainParams p;
   private final Rng rng;
   private final float[] occ;
   private final int size;
   private final double mpc;
   /** 生成结果。 */
   private final List<PlacedGroup> groups = new ArrayList();
   private final List<PlacedFeature> features = new ArrayList();
   private final List<PlacedParcel> parcels = new ArrayList();
   /** 建成区半径（米）：核心密、边缘疏，超出这个范围就不再排房（否则会沿着公路拉出长条）。 */
   private final double townRadius;
   /**
    * 建筑预算（世界→地图契约给出的数量上限）。
    *
    * <p>旧实现按 {@code townRadius} 一路铺满，12 户人家的岛也能铺出 485 栋房。预算是「世界数据 → 地图数量」
    * 的唯一通道：住宅 = 户数、附属房 = 户数×1.2、公共 = 设施清单、商铺/工坊/水工按户数派生。</p>
    */
   private final PlacementBudget budget;
   /** 已落位的主屋（住人的户）数量——与附属房分开计数。 */
   private int builtDwellings;
   /** 已落位的附属房（厢房/后院/柴房）数量。 */
   private int builtOutbuildings;
   /** 契约设施已经用掉的资产 code（灯塔只有一座、集市广场只有一块，沿街排房不许再抽中）。 */
   private final Set<String> facilityAssets = new HashSet();
   /**
    * 契约住所落位结果（homeRef → 图上那栋主屋）。
    *
    * <p>这是 M6「一户一屋」在落位器里的账本：世界数据里的每个住所必须对应图上唯一一栋具名主屋，
    * 坐标要能顺着 {@code homeRef} 回写到 {@code actor_npc_home}，角色才知道「我家在哪」。
    * 附属房命名（「渔家小院·柴房」）、菜畦归属、日程落点也都从这里取。</p>
    */
   private final Map<String, DwellingSite> dwellingSites = new LinkedHashMap();
   /**
    * 契约点名的「渡口/码头」。
    *
    * <p><b>为什么单独记</b>：码头不是房子——它必须从水线起步伸进深水，只能由 {@link #placeHarbor} 造。
    * 但名字必须来自世界数据（「月牙湾渡口」），不能退化成图上一条叫「码头」的木栈桥：
    * 世界观里写的地名一旦丢掉，日程落点、对话里的「去渡口」就都对不上东西了。</p>
    */
   private String harborName;
   /** 契约渡口的 code（写进语义，便于回写）。 */
   private String harborCode;
   /** 契约渡口挂在哪个世界地点。 */
   private String harborAnchor;
   /** 世界→地图契约（S4 的锚定依据；null = 走旧的随机铺满逻辑）。 */
   private WorldMapBrief brief;
   /** 田埂土路序号。 */
   private int fieldTrackSeq;
   /** 聚落中心（米）——农田、广场、街巷都按它算「离镇里多远」。 */
   private double settleX;
   private double settleZ;
   private int seq;
   /** 「建成物」占用场缓存（房子/广场，不含道路）：建房之后修路都按它避让。 */
   private float[] builtOcc;
   /** {@link #builtOcc} 缓存对应的 feature 数量：数量一变就重算（见 builtOccupancy）。 */
   private int builtOccCount = -1;
   /** 田块格标记（田与田之间只隔田埂，可以贴在一起）。 */
   private boolean[] fieldCell;
   /** 诊断计数（写进 stats.diagnostics，用于「为什么少了一栋/一块田」这类复盘）。 */
   private final Map<String, Integer> diag = new LinkedHashMap();
   private final List<Frontage> frontages = new ArrayList();
   // 田块尺度（12 米见方的地块 / 13 米间距 = 1 米田埂）在 farmBlock() 里就地定义。
   // 旧口径的条田常量（FIELD_CELL_W / FIELD_CELL_D / FIELD_TRACK_EVERY / FIELD_RIDGE）
   // 已随「正方形田块矩阵」方案作废并删除——不能让两套互相矛盾的田制参数同时留在代码里。
   private static final Map<String, String[]> GENERIC_BUILDING_NAMES = Map.of("民居", new String[]{"民居", "农舍", "老屋", "瓦房", "小院", "人家"}, "生产", new String[]{"作坊", "仓房", "晒场", "场院", "料房", "工棚"}, "工坊", new String[]{"工坊", "木作坊", "铁匠铺", "染坊", "油坊"}, "商铺", new String[]{"店铺", "货栈", "油盐铺", "茶棚"}, "公共", new String[]{"公所", "议事房", "库房", "更房"}, "水工", new String[]{"水房", "井房", "闸房"});
   private static final String[] GENERIC_FALLBACK_NAMES = new String[]{"房屋"};

   private MapPlacer(TerrainData t, TerrainParams p, long seed, PlacementBudget budget) {
      this.t = t;
      this.p = p;
      this.rng = new Rng(seed);
      this.size = t.size;
      this.mpc = Math.max(0.05, t.metersPerCell);
      this.occ = new float[this.size * this.size];
      this.budget = budget;
      this.townRadius = townRadiusMeters(t, p, budget);
   }

   /**
    * 聚落半径（米）——<b>S3 的街巷骨架和 S4 的落位必须用同一个数</b>。
    *
    * <p>为什么要把这个算法提出来：如果 S3 按地形半径（90~168 米）铺街、S4 按户数预算
    * （12 户 ≈ 82 米）排房，街就会比镇子长出七八十米，房子全挤在街心，镇外留着一条空街。
    * 两边同源之后，「街的长度」和「房子的规模」才是同一件事。</p>
    *
    * @param t      地形
    * @param p      地形参数
    * @param budget 落位预算（可为 null）
    * @return 半径（米）
    */
   public static double townRadiusMeters(TerrainData t, TerrainParams p, PlacementBudget budget) {
      double byTerrain = Math.max((double)90.0F, Math.min((double)168.0F, p.settlementRadius * (double)t.size * t.metersPerCell));
      if (budget == null) {
         return byTerrain;
      }
      double byCount = Math.sqrt((double)Math.max(1, budget.total()) * (double)420.0F / Math.PI);
      return Math.max((double)45.0F, Math.min(byTerrain, byCount));
   }

   public static Result place(TerrainData terrain, TerrainParams params, long seed, List<AssetSpec> assets, List<RoadSpec> roads, Map<String, MapSites.Site> sites) {
      return place(terrain, params, seed, assets, roads, sites, (PlacementBudget)null);
   }

   public static Result place(TerrainData terrain, TerrainParams params, long seed, List<AssetSpec> assets, List<RoadSpec> roads, Map<String, MapSites.Site> sites, WorldMapBrief brief) {
      MapPlacer placer = new MapPlacer(terrain, params, seed, MapPlacer.PlacementBudget.from(brief));
      placer.brief = brief;
      return placeWith(placer, terrain, params, seed, assets, roads, sites);
   }

   public static Result place(TerrainData terrain, TerrainParams params, long seed, List<AssetSpec> assets, List<RoadSpec> roads, Map<String, MapSites.Site> sites, PlacementBudget budget) {
      return placeWith(new MapPlacer(terrain, params, seed, budget), terrain, params, seed, assets, roads, sites);
   }

   /**
    * 主流程：<b>顺序本身就是设计</b>——改顺序会直接改变图的真实性。
    *
    * <ol>
    *   <li>路网整备：先长出街巷（StreetNetwork）、再焊接连通性（MapNetwork），得到的才是「一张连通的路网」；</li>
    *   <li>占位：水面、道路、广场先压进占用场，后面谁都不能盖在上面；</li>
    *   <li>契约设施 → 契约住所 → 院落 → 沿街排房 → 填空：地标占最好的位置，其次是有名有姓的人家，
    *       最后才用普通房子把缝填满（反过来就是「地标被挤到村外」）；</li>
    *   <li>菜畦必须在房子之后（菜畦要贴着自己家的院墙），农田必须在路网整备之后（田头路要接到村道上）；</li>
    *   <li>码头与桥最后做（它们依赖最终岸线/河道），然后剪掉「伸出聚落还在野地里跑」的路尾。</li>
    * </ol>
    *
    * @param placer 落位器
    * @param terrain 地形
    * @param params  地形参数
    * @param seed    种子
    * @param assets  资产类型库
    * @param roads   初始路网（会就地扩展）
    * @param sites   语义落位点
    * @return 落位结果
    */
   private static Result placeWith(MapPlacer placer, TerrainData terrain, TerrainParams params, long seed, List<AssetSpec> assets, List<RoadSpec> roads, Map<String, MapSites.Site> sites) {
      List<RoadSpec> grown = new ArrayList(roads);
      MapSites.Site core = (MapSites.Site)sites.get("core");
      if (core != null) {
         // 骨架（正街/后街/横巷）已经在 S3 由程序定好并接进了 net；这里只补「尽端胡同」，
         // 让街区内部有入户巷，而不是把房子直接摞在街上。
         grown.addAll(StreetNetwork.alleys(terrain, seed, roads, core.x(), core.z(), placer.townRadius));
      }

      MapNetwork.Result linked = MapNetwork.connect(terrain, grown);
      placer.diag.put("吸附", linked.snapped());
      placer.diag.put("连接路", linked.connectors());
      placer.diag.put("路网分量", linked.groupsAfter());
      List<RoadSpec> net = new ArrayList(linked.roads());
      // 去重必须在占位之前：重复路段会被 blockRoads 压两遍（路面比同级更宽），
      // 渲染时两条半透明路肩还会叠加成一条发亮的"怪路"。见 dedupeRoads 的注释。
      net = placer.dedupeKeepConnected(net);
      placer.settleX = core == null ? (double)placer.size * (double)0.5F * placer.mpc : core.x();
      placer.settleZ = core == null ? (double)placer.size * (double)0.5F * placer.mpc : core.z();
      placer.blockWater();
      placer.blockRoads(net);
      placer.placePlaza(sites, net);
      placer.placeAnchoredFacilities(assets, sites);
      // 设施落位之后，把「冲着这些目的地修的路」的端点重接到它门前（§V1.5-§7.1）。
      // 必须在设施之后、排房之前：晚了路已经被剪枝按"没人用"剪掉，早了设施还没落地。
      placer.snapRoadEndsToFacilities(net, placer.settleX, placer.settleZ);
      // 设施离所有路都太远（灯塔在海岬上、磨坊在河湾边）就按地形补一条进道：
      // 没有任何一条路的端点在那附近时，"把端点挪过去"是无解的。
      placer.connectFacilities(net);
      placer.placeBriefDwellings(net, assets, sites);
      placer.placeCompounds(net, assets, sites);
      placer.placeAlongRoads(net, assets, sites);
      double cx = core == null ? (double)placer.size * (double)0.5F * placer.mpc : core.x();
      double cz = core == null ? (double)placer.size * (double)0.5F * placer.mpc : core.z();
      placer.infillBlocks(net, assets, cx, cz);
      placer.sealFrontages(net);
      placer.placeKitchenGardens(assets);
      placer.capRoadEnds(net, assets, cx, cz);
      // 排房刚结束时量一次「几户没接路」：和收尾时的数一比，就知道问题出在落位口径
      // 还是出在之后的整备（拉直/剪尾把路挪开了）。
      placer.auditDoors(net, "排房后");
      placer.placeFarmlands(assets, sites, net);
      MapNetwork.Result weld = MapNetwork.connect(terrain, net, 0, 1.8, 48, placer.costWithClearance());
      net.clear();
      net.addAll(placer.dedupeKeepConnected(weld.roads()));
      placer.diag.merge("田路连接路", weld.connectors(), Integer::sum);
      placer.diag.put("田路吸附", weld.snapped());
      placer.shortcutRoads(net);
      placer.diag.put("全程分量", MapNetwork.groupCount(net));
      placer.placeForests(assets, sites);
      placer.placeHarbor(assets, sites, net);
      placer.placeBridges(net);
      placer.pruneDeadSpurs(net);
      placer.pruneUnbuiltTails(net);
      placer.trimUnservedTails(net);
      placer.pruneEmptyLongRoads(net);
      placer.pruneOrphans(net);
      // 端头必须领得到东西：把"最后一户之后还往前伸几十米"的野地尾巴收回去
      placer.trimDanglingTails(net);
      // 收尾再整备一次连通性：剪断头路 / 剪无建物尾段是「按一条路一条路判」的，
      // 剪掉的若是某片田路唯一的接入段，那片路就变成孤岛——一张网会被剪成好几块。
      // 所以剪完必须再过一遍吸附 + 补连接路（用 0 级 1.8 米田埂路，不会在田里修村道），
      // 保证「全图一张网」这条不变量在最后一步仍然成立。
      // 吸附距离放宽到 22 米：这一步留下的缺口多是 15~20 米，按默认 14 米接不上就只能补
      // 一段新路，而补出来的连接路在俯视图上恰恰是"又多又碎的短线"。
      MapNetwork.Result fin = MapNetwork.connect(terrain, net, 0, 1.8, 24, placer.costWithClearance(), 22.0);
      List<RoadSpec> finalRoads = placer.dedupeKeepConnected(fin.roads());
      // 不变量兜底：去重/拉直都有可能"顺手"剪掉一条恰好是唯一接点的路。
      // 只要发现不是一张网，就再补一次连接路（连接路本身不再参与去重）。
      if (MapNetwork.groupCount(finalRoads) > 1) {
         MapNetwork.Result fix = MapNetwork.connect(terrain, finalRoads, 0, 1.8, 24,
                 placer.costWithClearance(), 22.0);
         finalRoads = fix.roads();
         placer.diag.merge("收尾二次连接", fix.connectors(), Integer::sum);
      }
      // 最后只做「缝合」：不补任何新路（maxConnectors=0），只把十几米的缺口两头拉在一起。
      // 去重/拉直是"按一条路一条路替换折线"的，可能又把某个 T 字口拉开几米；
      // 缝合之后再判一次分量，确保交付的路网里没有"看着连着其实差十几米"的断点。
      MapNetwork.Result stitch = MapNetwork.connect(terrain, finalRoads, 0, 1.8, 0,
              placer.costWithClearance(), 22.0);
      finalRoads = stitch.roads();
      placer.diag.put("收尾缝合", stitch.snapped());
      // 最后一道外观整备：把吸附留下的"扎刺"折线抹平（不动端点在哪，只去掉来回折）。
      placer.despeckleRoads(finalRoads);
      // 再去掉「拐个直角然后十来米就没了」的路头钩子（剪路头钩）
      placer.trimEndHooks(finalRoads);
      // 剪掉「两头都挨着别的路、自己只有两三米」的碎路：它们不承担任何连接，
      // 只是把 T 字口描粗了一点点——图上就是路口处多出来的一小截疙瘩。
      placer.pruneTinyRoads(finalRoads);
      // 收尾再查一遍同走向重复：前面的去重是在「中途」的几何上判的，而缝合/去刺/剪钩每改一次
      // 折线，两条本来就压在一起的路就可能重合得更多（实测镇口那对 19/23 米的支线，中途只有 44%
      // 重合，收尾时是 75%）。只在这一步之后判，才判得准。
      finalRoads = placer.dedupeKeepConnected(finalRoads);
      // 去重会把"原来占着路口位置"的那条路拿走，于是剩下的路头就变成一个真正的断头钩子
      // （实测镇口那条 23 米 L 形支线：去重前它的端头离邻居 2 米，看着像路口，去重后才显出是钩子）。
      // 所以剪钩/剪碎要在去重之后再走一遍；它们是幂等的，多跑一次没有副作用。
      placer.trimEndHooks(finalRoads);
      placer.pruneTinyRoads(finalRoads);
      // 再清一遍「十几米、只接一头、什么也不服务」的尾巴路：上一道剪荒野尾有 12 米保底，
      // 剪不动整条都短的尾巴
      placer.pruneUnservedStubs(finalRoads);
      // 收尾再剪一次野地尾巴：前面那道剪荒野尾判"端头有没有用"时，用的还是「缝合前」的路网——
      // 那时可能有一条连接路恰好压在野尾上，把尾巴判成"有用"；那条连接路在之后的去重/剪碎里
      // 没了，尾巴就露出来了。到这里几何才算定稿，必须按定稿几何重判一次。
      placer.trimDanglingTails(finalRoads);
      // 剪完再补一次「几米级的缝」：路尾常常正好停在离别的路两三米的地方（不是没接上，
      // 只是差一点点），这种缝在俯视图上就是一个看得见的断点。只吸附、不补新路。
      MapNetwork.Result seam = MapNetwork.connect(terrain, finalRoads, 0, 1.8, 0,
              placer.costWithClearance(), 4.0);
      finalRoads = seam.roads();
      placer.diag.merge("收尾补缝", seam.snapped(), Integer::sum);
      // 路端扎进水里的先收回岸上：取水口的端头常在河面格上多走两三米，
      // 既不物理（路铺在水上）又会催生"跨到河心的假桥"。
      placer.trimTipsInWater(finalRoads);
      // 码头引道同样按<b>定稿路网</b>重接一次：它是在"当时那条路"上接的，之后剪尾/去重/缝合
      // 每改一次形，海边那一截就可能被剪掉——图上就是"村道到海岸还有几十米草地，栈桥孤零零伸在水里"。
      // 与桥是同一条理由：凡是"必须和定稿路网对齐"的水工物，都要在最后一步重铺。
      placer.reconnectWharf(finalRoads);
      // 最后一道「每户都进门」：所有沿街/镇外的独立房屋，门口离路超过 7 米就补一条入户小径，
      // 直接从门口连到<b>最近的那个路面点</b>（不是最近的顶点、更不是路头）。
      // 放在最后：拉直/剪尾/shap 之后路可能已经挪开几米，只有在这时量距离才是最终口径。
      placer.connectDwellingsToRoads(finalRoads);
      // 桥是按「当时的过河段」落的位：那之后路被剪尾、拉直、去重、补缝改过形，桥就可能留在原地，
      // 而它下面既没有路、也不在水上——图上就是野地里横着一块木板（实测码头旁边那座 9.8 米的桥，
      // 离最近的路 7 米、脚下是河汊却没有一条路从桥上过）。所以最后按<b>定稿路网</b>重铺一遍。
      placer.features.removeIf(feature -> "bridge".equals(feature.kind()));
      placer.placeBridges(finalRoads);
      // 桥的「标记 / 要素」对账：bridge_over=water 是 S3 按 A* 过水格数打的标，
      // 但有些路的过水段其实在<b>端头</b>（取水巷伸进河面那一两米）——这种不该有桥，
      // 旧管线就留下"路上标着桥、图上没有桥"的矛盾数据。这里把这种路的标记清掉，
      // 保证「有标记 ⇒ 有桥要素」；真的过河段（夹在路中间）已经在 placeBridges 里落了桥。
      placer.reconcileBridgeFlags(finalRoads);
      net.clear();
      net.addAll(finalRoads);
      // 收尾两道几何归一（必须在「所有落位与路网整备都结束」之后做，口径才是最终的）：
      // ① 房与房不允许相交：格网占用判据在 1 米格上按格心采样，斜放矩形 0.1~0.3 ㎡ 的
      //    角部相交整格漏过去，只能在多边形层面兜一次；
      // ② 门朝向统一由「门所在轮廓边的外法向」推——落位有 5 处各写一套 doorDir，
      //    口径必然漂移（实测一半房子与墙面法向相反），只能收口到一个几何定义。
      placer.separateBuildingOverlaps();
      placer.normalizeDoorDirections();
      placer.diag.merge("收尾连接路", fin.connectors(), Integer::sum);
      placer.diag.put("全程分量", MapNetwork.groupCount(net));
      placer.diag.put("宅院", placer.builtDwellings);
      placer.diag.put("附属房", placer.builtOutbuildings);
      if (placer.budget != null) {
         placer.diag.put("预算住宅", placer.budget.households());
         placer.diag.put("预算附属", placer.budget.outbuildings());
      }

      return new Result(placer.groups, placer.features, placer.parcels, net, placer.occ, placer.diag);
   }

   /**
    * 「近邻却绕远」的收口：非骨架的镇外路若两端之间直线走得通，就把整条路拉成直线。
    *
    * <p><b>为什么需要这一步</b>：S3 的目的地路用 A* 求线，A* 会为了「更缓、更少坡度」绕一大圈；
    * 而现实中两个近邻点之间的路往往就是一条直路（穿过林子、翻过缓坡）。用户看到的就是
    * 「本来可以就近接上，却绕到挺远的地方再接」。</p>
    *
    * <p><b>为什么只动非骨架路</b>：正街/后街/横巷是村庄形态的主体，拉直会把蜿蜒的村巷变成
    * 尺子画的直线（那是另一种"假"）；骨架之外的接路、目的地路、下地路才是"该直就直"的部分。</p>
    *
    * @param net 路网（就地替换折线）
    */
   private void shortcutRoads(List<RoadSpec> net) {
      if (net == null || net.isEmpty()) {
         return;
      }
      float[] cost = this.costWithClearance();
      List<RoadSpec> backup = new ArrayList(net);
      int before = MapNetwork.groupCount(net);
      int changed = 0;
      int stranding = 0;

      for(int i = 0; i < net.size(); ++i) {
         RoadSpec r = (RoadSpec)net.get(i);
         if (isSkeleton(r) || r.bridgeCells() > 0) {
            continue;   // 桥不能拉直：那是真的跨水
         }
         List<double[]> path = r.pathMeters();
         if (path == null || path.size() < 3) {
            continue;
         }
         List<double[]> straight = RoadRouter.straighten(this.t, cost, path);
         if (straight == path) {
            continue;
         }
         // 拉直会把路"抻"过去，原本贴着弯道盖的房子可能一夜之间离开路面十几米。
         // 用户的要求是「每间房都连着路」，而拉直只是一条观感修正——两者冲突时观感让位。
         if (this.strandsBuildings(r, straight)) {
            ++stranding;
            continue;
         }
         net.set(i, new RoadSpec(r.code(), r.name(), r.kind(), r.grade(), r.width(), r.surface(), straight, r.bridgeCells()));
         ++changed;
      }

      if (stranding > 0) {
         this.diag.merge("拉直避房", stranding, Integer::sum);
      }

      // 拉直是「换成弦」，可能把一条路的中间接点（T 字口）丢掉，于是路网散架。
      // 一旦分量变多就整批回滚：绕一点是观感问题，散架是数据问题。
      if (MapNetwork.groupCount(net) > before) {
         net.clear();
         net.addAll(backup);
         this.bump("拉直回滚");
         return;
      }

      if (changed > 0) {
         this.diag.merge("路拉直", changed, Integer::sum);
      }
   }

   /**
    * 去重 + <b>连通性回滚</b>：去重是「按一条路一条路判」的局部操作，可能顺手剪掉某片路唯一的接点。
    * 一旦发现分量数变多，就整批回滚——宁可留一条重复路，也不能让路网散成几块。
    *
    * @param roads 路网
    * @return 去重后的路网（若去重破坏了连通性则原样返回）
    */
   private List<RoadSpec> dedupeKeepConnected(List<RoadSpec> roads) {
      int before = MapNetwork.groupCount(roads);
      List<RoadSpec> after = this.dedupeRoads(roads);
      int now = MapNetwork.groupCount(after);
      if (now > before) {
         this.bump("去重回滚");
         return roads;
      }
      return after;
   }

   private void bump(String key) {
      this.diag.merge(key, 1, Integer::sum);
   }

   private void blockWater() {
      for(int i = 0; i < this.occ.length; ++i) {
         // 用 waterCell 而不是 height<=seaLevel：内陆湖的水面高于海平面，
         // 只按高程判会让房子和路落进湖里（旧版就有路横穿湖面）。
         if (this.t.waterCell(i)) {
            this.occ[i] = 1.0F;
         }
      }

   }

   private void blockRoads(List<RoadSpec> roads) {
      for(RoadSpec r : roads) {
         double half = Math.max((double)1.0F, r.width() / (double)2.0F + 0.4);
         List<double[]> path = r.pathMeters();

         for(int i = 1; i < path.size(); ++i) {
            this.stampCapsule((double[])path.get(i - 1), (double[])path.get(i), half, 1.0F);
         }
      }

   }

   private void stampCapsule(double[] a, double[] b, double half, float v) {
      double vx = b[0] - a[0];
      double vz = b[1] - a[1];
      double vv = vx * vx + vz * vz;
      int x0 = (int)Math.floor((Math.min(a[0], b[0]) - half) / this.mpc);
      int x1 = (int)Math.ceil((Math.max(a[0], b[0]) + half) / this.mpc);
      int z0 = (int)Math.floor((Math.min(a[1], b[1]) - half) / this.mpc);
      int z1 = (int)Math.ceil((Math.max(a[1], b[1]) + half) / this.mpc);

      for(int z = z0; z <= z1; ++z) {
         for(int x = x0; x <= x1; ++x) {
            if (this.okInBounds(x, z)) {
               double px = ((double)x + (double)0.5F) * this.mpc - a[0];
               double pz = ((double)z + (double)0.5F) * this.mpc - a[1];
               double t = vv < 1.0E-9 ? (double)0.0F : (px * vx + pz * vz) / vv;
               t = Math.max((double)0.0F, Math.min((double)1.0F, t));
               double dx = px - vx * t;
               double dz = pz - vz * t;
               if (dx * dx + dz * dz <= half * half) {
                  int i = z * this.size + x;
                  this.occ[i] = Math.max(this.occ[i], v);
               }
            }
         }
      }

   }

   /**
    * 离该点最近的街的走向（角度，度）。广场兜底落位时用它把广场摆成与街同向。
    *
    * @param roads 路网
    * @param x     X（米）
    * @param z     Z（米）
    * @return 走向（度）；无路返回 0
    */
   private double nearestRoadAngle(List<RoadSpec> roads, double x, double z) {
      double best = Double.MAX_VALUE;
      double ang = (double)0.0F;

      for(RoadSpec r : roads) {
         List<double[]> path = r.pathMeters();
         if (path != null && path.size() >= 2 && r.grade() >= 1) {
            for(int k = 1; k < path.size(); ++k) {
               double[] a = (double[])path.get(k - 1);
               double[] b = (double[])path.get(k);
               double tx = b[0] - a[0];
               double tz = b[1] - a[1];
               double len = Math.hypot(tx, tz);
               if (len < 3.0) {
                  continue;
               }
               double mx = (a[0] + b[0]) * (double)0.5F;
               double mz = (a[1] + b[1]) * (double)0.5F;
               double d = Math.hypot(mx - x, mz - z);
               if (d < best) {
                  best = d;
                  ang = Math.toDegrees(Math.atan2(tz, tx));
               }
            }
         }
      }

      return ang;
   }

   private void placePlaza(Map<String, MapSites.Site> sites, List<RoadSpec> roads) {
      MapSites.Site core = (MapSites.Site)sites.get("core");
      if (core != null) {
         // 广场尺度：旧值 30x24 / 26x20 在 68 米半径的小镇上等于一块 720 平米的空场，
         // 比村里最大的房子还大两倍，航拍上就是一块压住街口的灰板。真实村镇的集市/井台
         // 只有 10~18 米见方——「够摆几个摊、能转身」就够了，所以按 3~8 户的规模取小值。
         double[][] sizes = new double[][]{{(double)18.0F, (double)15.0F}, {(double)15.0F, (double)13.0F}, {(double)13.0F, (double)11.0F}, {(double)11.0F, (double)9.0F}};
         double px = (double)0.0F;
         double pz = (double)0.0F;
         double pw = (double)0.0F;
         double pd = (double)0.0F;
         double prot = (double)0.0F;
         double bestScore = Double.MAX_VALUE;
         boolean found = false;

         for(double[] sz : sizes) {
            for(RoadSpec r : roads) {
               List<double[]> path = r.pathMeters();
               if (path != null && path.size() >= 2 && r.grade() >= 1 && !"pier".equals(r.kind())) {
                  for(int k = 1; k < path.size(); ++k) {
                     double[] a = (double[])path.get(k - 1);
                     double[] b = (double[])path.get(k);
                     double tx = b[0] - a[0];
                     double tz = b[1] - a[1];
                     double len = Math.hypot(tx, tz);
                     if (!(len < sz[0] * 0.8)) {
                        tx /= len;
                        tz /= len;
                        double mx = (a[0] + b[0]) * (double)0.5F;
                        double mz = (a[1] + b[1]) * (double)0.5F;
                        if (!(Math.hypot(mx - core.x(), mz - core.z()) > (double)52.0F)) {
                           for(int side = -1; side <= 1; side += 2) {
                              for(double extra : new double[]{(double)1.0F, 2.2, 3.4}) {
                                 double off = r.width() * (double)0.5F + extra + sz[1] * (double)0.5F;
                                 double x = mx - tz * off * (double)side;
                                 double z = mz + tx * off * (double)side;
                                 double rot = Math.toDegrees(Math.atan2(tz, tx));
                                 if (this.canPlace(x, z, sz[0], sz[1], rot)) {
                                    double score = Math.hypot(x - core.x(), z - core.z()) + extra - sz[0] * sz[1] * 0.002;
                                    if (score < bestScore) {
                                       bestScore = score;
                                       px = x;
                                       pz = z;
                                       pw = sz[0];
                                       pd = sz[1];
                                       prot = rot;
                                       found = true;
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }

            if (found) {
               break;
            }
         }

         if (!found) {
            double[] radii = new double[]{(double)6.0F, (double)10.0F, (double)14.0F, (double)18.0F, (double)24.0F, (double)30.0F};

            for(double[] sz : sizes) {
               for(double rr : radii) {
                  for(int k = 0; k < 16 && !found; ++k) {
                     double ang = (double)k * Math.PI / (double)8.0F;
                     double x = core.x() + Math.cos(ang) * rr;
                     double z = core.z() + Math.sin(ang) * rr;
                     // 广场必须和它靠着的街同向：旧兜底固定 rot=0，于是街上斜着、广场方方正正，
                     // 一眼就是"两种坐标系拼在一起"。这里取最近一条街的切向作为广场长边方向。
                     double rotDeg = this.nearestRoadAngle(roads, x, z);
                     if (this.canPlace(x, z, sz[0], sz[1], rotDeg) && this.touchesRoad(roads, x, z, Math.max(sz[0], sz[1]) * (double)0.5F + (double)9.0F)) {
                        px = x;
                        pz = z;
                        pw = sz[0];
                        pd = sz[1];
                        prot = rotDeg;
                        found = true;
                     }
                  }

                  if (found) {
                     break;
                  }
               }

               if (found) {
                  break;
               }
            }
         }

         if (!found) {
            this.bump("广场放不下");
         } else {
            String code = this.nextCode("plaza");
            double[][] poly = this.rect(px, pz, pw, pd, prot);
            this.commit(px, pz, pw, pd, prot);
            this.features.add(new PlacedFeature(code, "镇中心广场", "公共", "plaza", (String)null, px, pz, pw, pd, prot, 0.05, 1, this.elevationAt(px, pz), (String)null, "#b9ab94", (String)null, px, pz + pd / (double)2.0F, (double)180.0F, poly, "{\"role\":\"plaza\"}", "core", 0, 4));
            this.parcels.add(new PlacedParcel(this.nextCode("parcel_plaza"), "plaza", px, pz, pw * pd, (double)1.0F, poly, (String)null, code));
            this.groups.add(new PlacedGroup(this.nextCode("grp_plaza"), "镇中心广场", "plaza", px, pz, Math.max(pw, pd) / (double)2.0F, prot, "core", "全图枢纽：井台、告示牌、市集"));
         }
      }
   }

   /**
    * 把「世界数据里点名的设施」落到它的锚点旁边。
    *
    * <p><b>为什么需要这一步</b>：旧管线里设施是「在路边随机抽一个类型」，于是世界观写着「北端海岬有灯塔」，
    * 图上却是一栋不知道在哪儿的普通房子——地标和它的方位、位置、与聚落的关系全丢了。契约给了每个设施的
    * {@code anchorLocation}（挂到哪个世界地点），这里就按「地点 → 语义点 / 区域方位 → 在附近找一块能建的地」
    * 把它放下去。</p>
    *
    * <p>顺序上必须在沿街排房之前：地标要占最好的位置，而不是等别人占满了再来挤。码头例外——
    * 它必须从水线起步伸进深水，只能交给 {@link #placeHarbor} 统一造，这里只记下契约给的名字。</p>
    *
    * @param assets 资产类型库
    * @param sites  语义落位点
    */
   private void placeAnchoredFacilities(List<AssetSpec> assets, Map<String, MapSites.Site> sites) {
      if (this.brief != null && this.brief.facilities() != null && !this.brief.facilities().isEmpty()) {
         Map<String, MapSites.Site> byName = new HashMap();

         for(MapSites.Site s : sites.values()) {
            if (s.name() != null && !s.name().isBlank()) {
               byName.put(s.name(), s);
            }
         }

         int placed = 0;
         Set<String> placedCodes = new HashSet();

         for(WorldMapBrief.Facility f : this.brief.facilities()) {
            if (f != null) {
               if (this.budgetFull()) {
                  this.bump("设施:预算满");
                  break;
               }

               if (f.code() != null && !placedCodes.add(f.code())) {
                  this.bump("设施:重复:" + f.code());
               } else {
                  AssetSpec spec = findForFacility(assets, f);
                  if (spec == null) {
                     this.bump("设施:无类型");
                  } else if ("pier".equals(spec.kind())) {
                     if (this.harborName == null) {
                        this.harborName = f.name();
                        this.harborCode = f.code();
                        this.harborAnchor = f.anchorLocation();
                     }

                     this.bump("设施:码头交港湾");
                  } else if (!this.facilityAssets.add(spec.code())) {
                     this.bump("设施:资产重复:" + f.code());
                  } else {
                     double[] anchor = this.anchorOf(f, sites, byName);
                     if (anchor == null) {
                        this.bump("设施:无锚点:" + f.code());
                     } else if (this.placeOneAt(anchor[0], anchor[1], spec, f)) {
                        ++placed;
                     } else {
                        this.facilityAssets.remove(spec.code());
                        this.bump("设施:放不下:" + f.code());
                     }
                  }
               }
            }
         }

         if (placed > 0) {
            this.diag.merge("契约设施", placed, Integer::sum);
         }

      }
   }

   private double[] anchorOf(WorldMapBrief.Facility f, Map<String, MapSites.Site> sites, Map<String, MapSites.Site> byName) {
      String loc = f.anchorLocation();
      if (loc != null && !loc.isBlank()) {
         MapSites.Site s = (MapSites.Site)byName.get(loc);
         if (s != null && !"landmark".equals(s.role())) {
            return new double[]{s.x(), s.z()};
         }

         if (this.p.regions != null) {
            for(TerrainRegion r : this.p.regions) {
               if (loc.equals(r.name()) || loc.equals(r.code())) {
                  double ang = TerrainRegion.bearingToRad(r.bearingDeg());
                  double mid = (r.radialFrom() + Math.min(r.radialTo(), r.radialFrom() + 0.4)) * (double)0.5F;
                  double rr = Math.max((double)1.0F, (double)this.size * Math.max(0.1, this.p.islandRadiusRatio)) * mid;
                  return new double[]{(double)this.size * (double)0.5F * this.mpc + Math.cos(ang) * rr, (double)this.size * (double)0.5F * this.mpc + Math.sin(ang) * rr};
               }
            }
         }
      }

      String key = AssetSemantics.key(f.code(), f.name(), f.kind());
      if ("lighthouse".equals(key)) {
         double[] ch = this.coastalHigh();
         if (ch != null) {
            return ch;
         }
      }

      String var10000;
      switch (key) {
         case "lighthouse" -> var10000 = "high_ground";
         case "pier" -> var10000 = "gate";
         case "mill" -> var10000 = "waterfront";
         default -> var10000 = "core";
      }

      String prefer = var10000;
      MapSites.Site s = (MapSites.Site)sites.get(prefer);
      if (s == null) {
         s = (MapSites.Site)sites.get("core");
      }

      return s == null ? null : new double[]{s.x(), s.z()};
   }

   private double[] coastalHigh() {
      if (this.p.regions != null) {
         double[] best = null;
         double bestH = (double)-1.0F;

         for(TerrainRegion r : this.p.regions) {
            if ("CAPE".equals(r.kind())) {
               double[] tip = this.coastAlong(r.bearingDeg());
               if (tip != null) {
                  double[] hi = this.highestNear(tip[0], tip[1], Math.max((double)60.0F, (double)this.size * 0.09));
                  double h = hi == null ? (double)-1.0F : this.elevationAt(hi[0], hi[1]);
                  if (hi != null && h > bestH) {
                     bestH = h;
                     best = hi;
                  }
               }
            }
         }

         if (best != null) {
            return best;
         }
      }

      List<Double> preferDeg = new ArrayList();
      if (this.p.regions != null) {
         for(TerrainRegion r : this.p.regions) {
            if ("CAPE".equals(r.kind()) || "HILLS".equals(r.kind())) {
               preferDeg.add(r.bearingDeg());
            }
         }
      }

      double cx = (double)this.size * (double)0.5F;
      double cz = (double)this.size * (double)0.5F;
      double maxH = Math.max(0.02, this.t.maxHeight - this.t.seaLevel);
      int bestI = -1;
      double bestScore = (double)-1.0F;

      for(int z = 2; z < this.size - 2; ++z) {
         for(int x = 2; x < this.size - 2; ++x) {
            int i = z * this.size + x;
            if (!((double)this.t.height[i] <= this.t.seaLevel + 0.01)) {
               double dSea = (double)this.t.distSea[i];
               if (!(dSea < (double)8.0F) && !(dSea > (double)45.0F) && !(this.t.buildable[i] < 0.05F)) {
                  double hn = Math.min((double)1.0F, ((double)this.t.height[i] - this.t.seaLevel) / maxH);
                  double score = hn * 2.2 + ((double)1.0F - dSea / (double)45.0F) * 0.55 + (double)this.t.buildable[i] * (double)0.5F;
                  if (!preferDeg.isEmpty()) {
                     double ang = Math.toDegrees(Math.atan2((double)z + (double)0.5F - cz, (double)x + (double)0.5F - cx));
                     double diff = (double)180.0F;

                     for(double b : preferDeg) {
                        double want = b - (double)90.0F;
                        double d = Math.abs(((ang - want) % (double)360.0F + (double)540.0F) % (double)360.0F - (double)180.0F);
                        diff = Math.min(diff, d);
                     }

                     score -= Math.min(1.8, diff / (double)30.0F * 0.9);
                  }

                  if (score > bestScore) {
                     bestScore = score;
                     bestI = i;
                  }
               }
            }
         }
      }

      if (bestI < 0) {
         return null;
      } else {
         return new double[]{(double)(bestI % this.size) * this.mpc + this.mpc * (double)0.5F, (double)(bestI / this.size) * this.mpc + this.mpc * (double)0.5F};
      }
   }

   private double[] coastAlong(double bearingDeg) {
      double ang = TerrainRegion.bearingToRad(bearingDeg);
      double cx = (double)this.size * (double)0.5F;
      double cz = (double)this.size * (double)0.5F;
      double step = Math.max((double)1.0F, this.mpc);
      double maxR = (double)this.size * (double)0.5F;
      int lastX = -1;
      int lastZ = -1;

      for(double r = step * (double)4.0F; r <= maxR; r += step) {
         int x = (int)Math.round(cx + Math.cos(ang) * r);
         int z = (int)Math.round(cz + Math.sin(ang) * r);
         if (x < 1 || z < 1 || x >= this.size - 1 || z >= this.size - 1) {
            break;
         }

         if ((double)this.t.height[z * this.size + x] > this.t.seaLevel + 0.002) {
            lastX = x;
            lastZ = z;
         } else if (lastX >= 0) {
            break;
         }
      }

      if (lastX < 0) {
         return null;
      } else {
         double backCells = (double)25.0F / this.mpc;
         return new double[]{((double)lastX - Math.cos(ang) * backCells) * this.mpc, ((double)lastZ - Math.sin(ang) * backCells) * this.mpc};
      }
   }

   private double[] highestNear(double px, double pz, double radius) {
      int cx = (int)Math.round(px / this.mpc);
      int cz = (int)Math.round(pz / this.mpc);
      int rr = Math.max(2, (int)Math.ceil(radius / this.mpc));
      int bestI = -1;
      double bestH = (double)-1.0F;
      int bestLoose = -1;
      double bestLooseH = (double)-1.0F;

      for(int z = cz - rr; z <= cz + rr; ++z) {
         for(int x = cx - rr; x <= cx + rr; ++x) {
            if (x >= 2 && z >= 2 && x < this.size - 2 && z < this.size - 2 && !(Math.hypot((double)(x - cx), (double)(z - cz)) > (double)rr)) {
               int i = z * this.size + x;
               if (!((double)this.t.height[i] <= this.t.seaLevel + 0.01)) {
                  if (this.t.buildable[i] < 0.05F) {
                     if ((double)this.t.height[i] > bestLooseH) {
                        bestLooseH = (double)this.t.height[i];
                        bestLoose = i;
                     }
                  } else if ((double)this.t.height[i] > bestH) {
                     bestH = (double)this.t.height[i];
                     bestI = i;
                  }
               }
            }
         }
      }

      if (bestI < 0) {
         bestI = bestLoose;
      }

      if (bestI < 0) {
         return null;
      } else {
         return new double[]{(double)(bestI % this.size) * this.mpc + this.mpc * (double)0.5F, (double)(bestI / this.size) * this.mpc + this.mpc * (double)0.5F};
      }
   }

   private boolean placeOneAt(double ax, double az, AssetSpec spec, WorldMapBrief.Facility f) {
      double w = spec.baseW();
      double d = spec.baseD();
      double nearR = Math.max((double)40.0F, Math.min((double)160.0F, Math.max(w, d) * (double)4.0F));
      return this.placeInRing(ax, az, spec, f, nearR, (double)4.0F, 0.1F) ? true : this.placeInRing(ax, az, spec, f, Math.max((double)160.0F, nearR * 2.6), (double)6.0F, 0.0F);
   }

   private boolean placeInRing(double ax, double az, AssetSpec spec, WorldMapBrief.Facility f, double maxR, double step, float minBuildable) {
      double w = spec.baseW();
      double d = spec.baseD();

      for(double r = (double)0.0F; r <= maxR; r += step) {
         int steps = r < (double)1.0F ? 1 : Math.max(8, (int)Math.round(r * 1.1));

         for(int k = 0; k < steps; ++k) {
            double ang = (Math.PI * 2D) * (double)k / (double)steps;
            double x = ax + Math.cos(ang) * r;
            double z = az + Math.sin(ang) * r;
            int i = this.cellIndex(x, z);
            if (i >= 0 && !(this.t.buildable[i] < minBuildable)) {
               double rot = Math.toDegrees(Math.atan2(-(this.settleX - x), this.settleZ - z));
               if (this.canPlace(x, z, w, d, rot, minBuildable)) {
                  this.commit(x, z, w, d, rot);
                  int floors = spec.floorsMin() + (spec.floorsMax() > spec.floorsMin() ? this.rng.nextInt(spec.floorsMax() - spec.floorsMin() + 1) : 0);
                  double height = (double)floors * (3.2 + this.rng.nextDouble() * 0.9);
                  double rad = Math.toRadians(rot);
                  double doorX = x - Math.sin(rad) * (d / (double)2.0F);
                  double doorZ = z + Math.cos(rad) * (d / (double)2.0F);
                  String code = this.nextCode("f_" + spec.code());
                  String key = AssetSemantics.key(f.code(), f.name(), f.kind());
                  boolean openGround = "market".equals(key) || "plaza".equals(key) || "square".equals(key) || "yard".equals(key);
                  String anchorEsc = f.anchorLocation() == null ? "" : f.anchorLocation().replace("\"", "");
                  if (openGround) {
                     this.features.add(new PlacedFeature(code, spec.name(), "公共", "plaza", (String)null, x, z, w, d, rot, 0.08, 1, this.elevationAt(x, z), (String)null, spec.roofColor() == null ? "#b8aa92" : spec.roofColor(), (String)null, doorX, doorZ, rot, this.rect(x, z, w, d, rot), "{\"facility\":true,\"role\":\"market\",\"key\":\"" + key + "\",\"anchor\":\"" + anchorEsc + "\"}", (String)null, 0, 4));
                  } else {
                     this.features.add(new PlacedFeature(code, spec.name(), spec.category(), "building", spec.code(), x, z, w, d, rot, height, floors, this.elevationAt(x, z), spec.wallColor(), spec.roofColor(), spec.roofShape(), doorX, doorZ, rot, this.rect(x, z, w, d, rot), "{\"facility\":true,\"key\":\"" + key + "\",\"anchor\":\"" + anchorEsc + "\"}", (String)null, 0, 3));
                  }

                  this.parcels.add(new PlacedParcel(this.nextCode("parcel_facility"), "facility", x, z, w * d, (double)this.t.buildable[i], this.rect(x, z, w, d, rot), (String)null, code));
                  this.groups.add(new PlacedGroup(this.nextCode("grp_" + spec.code()), spec.name(), "landmark", x, z, Math.max(w, d) / (double)2.0F, rot, spec.code(), f.role() == null ? "契约设施" : f.role()));
                  return true;
               }
            }
         }
      }

      return false;
   }

   private static AssetSpec findByCode(List<AssetSpec> assets, String code) {
      if (code == null) {
         return null;
      } else {
         for(AssetSpec a : assets) {
            if (code.equals(a.code())) {
               return a;
            }
         }

         return null;
      }
   }

   private static AssetSpec findForFacility(List<AssetSpec> assets, WorldMapBrief.Facility f) {
      AssetSpec exact = findByCode(assets, f.code());
      if (exact != null) {
         return exact;
      } else {
         String key = AssetSemantics.key(f.code(), f.name(), f.kind());
         if (key.isEmpty()) {
            return null;
         } else {
            AssetSpec best = null;

            for(AssetSpec a : assets) {
               if (("building".equals(a.kind()) || "pier".equals(a.kind())) && key.equals(AssetSemantics.key(a.code(), a.name(), (String)null)) && (best == null || "民居".equals(best.category()) && !"民居".equals(a.category()))) {
                  best = a;
               }
            }

            return best;
         }
      }
   }

   /**
    * 契约住所落位：<b>一户一屋、主屋具名、附属房跟着主屋</b>。
    *
    * <p>世界数据里的每个住所（{@code actor_npc_home}）都要在图上变成一栋「有名字的主屋」，
    * 坐标顺着 {@code homeRef} 回写，角色才知道自家门朝哪、睡哪间、菜畦在哪。旧实现是随机排房，
    * 于是「12 户人家」在图上没有身份：日程只能笼统写「在家」，对话也说不出「回沈家大宅」。</p>
    *
    * <p>落位顺序：① 沿路找正面朝街的空地 → ② 按户主名命名主屋（「沈家大宅」）→ ③ 院内补厢房/柴房
    * （「沈家大宅·东厢房」）→ ④ 登记 {@link DwellingSite} 供菜畦与日程使用。</p>
    *
    * @param roads  路网
    * @param assets 资产类型库
    * @param sites  语义落位点
    */
   private void placeBriefDwellings(List<RoadSpec> roads, List<AssetSpec> assets, Map<String, MapSites.Site> sites) {
      if (this.brief != null && this.brief.dwellings() != null && !this.brief.dwellings().isEmpty()) {
         AssetSpec home = this.pickByCategory(assets, "民居");
         if (home != null) {
            List<RoadSpec> ordered = new ArrayList();

            for(RoadSpec r : roads) {
               if (!"pier".equals(r.kind()) && r.pathMeters() != null && r.pathMeters().size() >= 2) {
                  ordered.add(r);
               }
            }

            ordered.sort((a, b) -> Integer.compare(b.grade(), a.grade()));
            if (ordered.isEmpty()) {
               this.bump("住所:无路可依");
            } else {
               Map<String, MapSites.Site> byName = new HashMap();

               for(MapSites.Site s : sites.values()) {
                  if (s.name() != null && !s.name().isBlank()) {
                     byName.put(s.name(), s);
                  }
               }

               int made = 0;
               int cursor = 0;

               for(WorldMapBrief.Dwelling d : this.brief.dwellings()) {
                  if (d != null) {
                     if (this.budget != null && this.builtDwellings >= this.budget.households()) {
                        break;
                     }

                     String ref = d.homeRef() == null ? "" : d.homeRef().trim();
                     if (ref.isEmpty()) {
                        this.bump("住所:无引用");
                     } else if (this.dwellingSites.containsKey(ref)) {
                        this.bump("住所:重复引用:" + ref);
                     } else {
                        String name = d.homeName() != null && !d.homeName().isBlank() ? d.homeName().trim() : null;
                        String anchor = d.anchorLocation() == null ? "" : d.anchorLocation().trim();
                        int members = d.members() == null ? 0 : d.members().size();
                        MapSites.Site anchorSite = anchor.isEmpty() ? null : (MapSites.Site)byName.get(anchor);
                        List<RoadSpec> pool = ordered;
                        if (anchorSite != null) {
                           double ax = anchorSite.x();
                           double az = anchorSite.z();
                           pool = new ArrayList(ordered);
                           pool.sort(Comparator.comparingDouble((rx) -> distToPath(rx, ax, az)));
                        }

                        boolean wantCompound = members >= 4 && this.rng.nextDouble() < 0.78;
                        boolean ok = false;
                        int tries = pool.size() * 2;

                        for(int attempt = 0; attempt < tries && !ok; ++attempt) {
                           RoadSpec road = (RoadSpec)pool.get((cursor + attempt) % pool.size());
                           if (wantCompound) {
                              ok = this.tryCompoundOnRoad(road, home, name, ref, anchor);
                           }

                           if (!ok) {
                              ok = this.tryDwellingOnRoad(road, home, name, ref, anchor, members);
                           }
                        }

                        if (ok) {
                           ++made;
                           ++cursor;
                        } else {
                           this.bump("住所:放不下:" + ref);
                        }
                     }
                  }
               }

               this.diag.put("契约住所", made);
               if (made < this.brief.dwellings().size()) {
                  this.diag.put("契约住所缺口", this.brief.dwellings().size() - made);
               }

            }
         }
      }
   }

   private static double distToPath(RoadSpec road, double x, double z) {
      double best = Double.MAX_VALUE;
      List<double[]> path = road.pathMeters();

      for(int i = 1; i < path.size(); ++i) {
         double[] a = (double[])path.get(i - 1);
         double[] b = (double[])path.get(i);
         double vx = b[0] - a[0];
         double vz = b[1] - a[1];
         double vv = vx * vx + vz * vz;
         double t = vv < 1.0E-9 ? (double)0.0F : ((x - a[0]) * vx + (z - a[1]) * vz) / vv;
         t = Math.max((double)0.0F, Math.min((double)1.0F, t));
         best = Math.min(best, Math.hypot(x - (a[0] + vx * t), z - (a[1] + vz * t)));
      }

      return best;
   }

   private boolean tryDwellingOnRoad(RoadSpec road, AssetSpec spec, String homeName, String homeRef, String anchor, int members) {
      List<double[]> path = road.pathMeters();
      double[] cum = arcLengths(path);
      double total = cum[cum.length - 1];
      if (total < (double)6.0F) {
         return false;
      } else {
         double scale = (double)1.0F + Math.min(0.28, (double)members * 0.05);

         for(double s = (double)3.0F; s < total - (double)3.0F; s += (double)6.5F) {
            double[] p = pointAt(path, cum, s);
            double[] tan = chordDir(path, cum, s, (double)7.0F);
            if (tan != null) {
               for(int side = -1; side <= 1; side += 2) {
                  double nx = -tan[1] * (double)side;
                  double nz = tan[0] * (double)side;
                  double w = Math.max(5.2, spec.baseW() * scale * (0.94 + this.rng.nextDouble() * 0.18));
                  double d = Math.max(4.4, spec.baseD() * (0.94 + this.rng.nextDouble() * 0.16));
                  double setback = 1.4 * (0.8 + this.rng.nextDouble() * 0.9);
                  double offset = road.width() / (double)2.0F + setback + d / (double)2.0F;
                  double cx = p[0] + nx * offset;
                  double cz = p[1] + nz * offset;
                  // 立面必须与路平行：长边沿切线（tan），不是沿"指向路心"的法线
                  double rot = Math.toDegrees(Math.atan2(tan[1], tan[0]));
                  if (this.canPlace(cx, cz, w, d, rot)) {
                     this.commit(cx, cz, w, d, rot);
                     String code = this.nextCode("f_home");
                     int floors = spec.floorsMin() + (spec.floorsMax() > spec.floorsMin() ? this.rng.nextInt(Math.min(2, spec.floorsMax() - spec.floorsMin() + 1)) : 0);
                     double height = (double)floors * (3.1 + this.rng.nextDouble() * 0.7);
                     double[][] poly = this.rect(cx, cz, w, d, rot);
                     double doorX = cx - nx * (d / (double)2.0F);
                     double doorZ = cz - nz * (d / (double)2.0F);
                     this.features.add(new PlacedFeature(code, homeName == null ? spec.name() : homeName, "民居", "building", spec.code(), cx, cz, w, d, rot, height, Math.max(1, floors), this.elevationAt(cx, cz), spec.wallColor(), spec.roofColor(), spec.roofShape(), doorX, doorZ, rot, poly, "{\"dwelling\":true,\"homeRef\":\"" + homeRef + "\",\"anchor\":\"" + anchor.replace("\"", "") + "\",\"members\":" + members + "}", (String)null, 1, 2));
                     this.parcels.add(new PlacedParcel(this.nextCode("parcel_home"), "homestead", cx, cz, w * d + (double)6.0F, (double)this.t.buildable[Math.max(0, this.cellIndex(cx, cz))], poly, road.code(), code));
                     this.groups.add(new PlacedGroup(this.nextCode("grp_home"), homeName == null ? "民居" : homeName, "courtyard", cx, cz, Math.max(w, d) / (double)2.0F, rot, spec.code(), homeName == null ? "一户人家" : homeName + "（" + members + " 人）"));
                     this.buildRearOutbuilding(cx, cz, w, d, nx, nz, rot, spec, homeName, homeRef, height);
                     ++this.builtDwellings;
                     this.dwellingSites.put(homeRef, new DwellingSite(homeRef, homeName, code, cx, cz, doorX, doorZ, anchor, members));
                     return true;
                  }
               }
            }
         }

         return false;
      }
   }

   private void buildRearOutbuilding(double cx, double cz, double w, double d, double nx, double nz, double rot, AssetSpec spec, String homeName, String homeRef, double mainHeight) {
      if (!this.outbuildingsFull()) {
         double bw = w * (0.52 + this.rng.nextDouble() * 0.22);
         double bd = d * (0.62 + this.rng.nextDouble() * 0.22);
         double backGap = (double)2.0F + this.rng.nextDouble() * 2.4;
         double bx = cx + nx * (d / (double)2.0F + backGap + bd / (double)2.0F);
         double bz = cz + nz * (d / (double)2.0F + backGap + bd / (double)2.0F);
         if (this.canPlace(bx, bz, bw, bd, rot)) {
            this.commit(bx, bz, bw, bd, rot);
            boolean shed = this.rng.nextDouble() < 0.45;
            String suffix = shed ? "柴房" : "灶房";
            String name = homeName == null ? suffix : homeName + "·" + suffix;
            this.features.add(new PlacedFeature(this.nextCode(shed ? "f_shed" : "f_kitchen"), name, "民居", "building", spec.code(), bx, bz, bw, bd, rot, Math.max(2.2, mainHeight * 0.55), 1, this.elevationAt(bx, bz), spec.wallColor(), rearRoof(spec.roofColor()), shed ? "shed" : "gable", bx - nx * (bd / (double)2.0F), bz - nz * (bd / (double)2.0F), rot, this.rect(bx, bz, bw, bd, rot), "{\"annex\":true,\"owner\":\"" + homeRef + "\"}", (String)null, 0, 1));
            this.parcels.add(new PlacedParcel(this.nextCode("parcel_annex"), "homestead", bx, bz, bw * bd, (double)this.t.buildable[Math.max(0, this.cellIndex(bx, bz))], this.rect(bx, bz, bw, bd, rot), (String)null, (String)null));
            ++this.builtOutbuildings;
         }
      }
   }

   private boolean tryCompoundOnRoad(RoadSpec road, AssetSpec home, String homeName, String homeRef, String anchor) {
      List<double[]> path = road.pathMeters();
      if (path.size() < 2) {
         return false;
      } else {
         double[] cum = arcLengths(path);
         double total = cum[cum.length - 1];
         boolean alley = "alley".equals(road.kind());
         double step = alley ? (double)16.0F : (double)20.0F;

         for(double s = (double)4.0F; s < total; s += step) {
            double[] p = pointAt(path, cum, s);
            double[] tan = chordDir(path, cum, s, (double)7.0F);
            if (tan != null) {
               for(int side = -1; side <= 1; side += 2) {
                  if (this.tryCompound(p[0], p[1], -tan[1] * (double)side, tan[0] * (double)side, road, home, this.settleX, this.settleZ, homeName, homeRef, anchor)) {
                     return true;
                  }
               }
            }
         }

         return false;
      }
   }

   private void placeCompounds(List<RoadSpec> roads, List<AssetSpec> assets, Map<String, MapSites.Site> sites) {
      MapSites.Site core = (MapSites.Site)sites.get("core");
      double coreX = core == null ? (double)this.size * (double)0.5F * this.mpc : core.x();
      double coreZ = core == null ? (double)this.size * (double)0.5F * this.mpc : core.z();
      AssetSpec home = this.pickByCategory(assets, "民居");
      if (home != null) {
         double chance = this.compoundChance();
         int made = 0;
         int maxCompounds = this.budget == null ? 30 : Math.max(0, this.budget.households() / 4);
         if (this.budget != null && this.builtDwellings > 0) {
            maxCompounds = Math.max(0, (this.budget.households() - this.builtDwellings) / 4);
         }

         if (!this.budgetFull() && !this.dwellingsFull() && maxCompounds > 0) {
            List<RoadSpec> ordered = new ArrayList(roads);
            ordered.sort((ax, bx) -> Integer.compare(bx.grade(), ax.grade()));

            for(RoadSpec road : ordered) {
               if (made >= maxCompounds) {
                  break;
               }

               if (road.pathMeters().size() >= 2 && !"pier".equals(road.kind())) {
                  boolean alley = "alley".equals(road.kind());
                  double step = alley ? (double)22.0F : (double)27.0F;
                  List<double[]> path = road.pathMeters();

                  for(int i = 1; i < path.size(); ++i) {
                     double[] a = (double[])path.get(i - 1);
                     double[] b = (double[])path.get(i);
                     double segLen = Math.hypot(b[0] - a[0], b[1] - a[1]);
                     if (!(segLen < 1.0E-6)) {
                        double ux = (b[0] - a[0]) / segLen;
                        double uz = (b[1] - a[1]) / segLen;
                        double nx = -uz;
                        double nz = ux;

                        for(double s = (double)4.0F; s < segLen; s += step) {
                           if (!(this.rng.nextDouble() > (alley ? chance * 1.35 : chance))) {
                              double px = a[0] + ux * s;
                              double pz = a[1] + uz * s;

                              for(int side = -1; side <= 1 && made < maxCompounds; side += 2) {
                                 if (this.tryCompound(px, pz, nx * (double)side, nz * (double)side, road, home, coreX, coreZ, (String)null, (String)null, (String)null)) {
                                    ++made;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }

            if (made > 0) {
               this.diag.merge("院落", made, Integer::sum);
            }

         }
      }
   }

   private double compoundChance() {
      String g = this.p.genreProfile == null ? "" : this.p.genreProfile;
      if (!"river_valley_village".equals(g) && !"mountain_hamlet".equals(g)) {
         return "plain_city".equals(g) ? 0.1 : 0.24;
      } else {
         return 0.46;
      }
   }

   private boolean tryCompound(double px, double pz, double nx, double nz, RoadSpec road, AssetSpec home, double coreX, double coreZ, String homeName, String homeRef, String anchor) {
      // 三合院的正屋必须与街道平行：rot 让局部 x 轴 = 道路切线 (-nz, nx)。
      // 旧写法把法线当长边方向，院子整个斜着摆在街上——「三合院」的"合"就散了。
      double rot = Math.toDegrees(Math.atan2(nx, -nz));
      double off = road.width() / (double)2.0F + (double)1.0F + 8.7;
      double cx = px + nx * off;
      double cz = pz + nz * off;
      if (!this.canPlace(cx, cz, 17.6, 17.4, rot)) {
         return false;
      } else {
         double[][] plan = new double[][]{{(double)0.0F, -5.6, 10.4, 6.2, (double)0.0F}, {5.6, (double)0.5F, 4.6, 7.6, (double)1.0F}, {-5.6, (double)0.5F, 4.6, 7.6, (double)1.0F}, {(double)0.0F, (double)7.0F, 3.6, 2.8, (double)2.0F}, {-5.3, 7.7, 7.4, 0.45, (double)3.0F}, {5.3, 7.7, 7.4, 0.45, (double)3.0F}, {-8.1, -1.4, 0.45, 12.6, (double)3.0F}, {8.1, -1.4, 0.45, 12.6, (double)3.0F}, {(double)0.0F, (double)-9.0F, 16.6, 0.45, (double)3.0F}};

         for(double[] q : plan) {
            double[] w = this.local(cx, cz, rot, q[0], q[1]);
            if (!this.canPlace(w[0], w[1], q[2], q[3], rot)) {
               this.bump("院落放不下");
               return false;
            }
         }

         List<String> codes = new ArrayList();
         String wall = home.wallColor();
         String roof = home.roofColor();
         double floorsH = 3.2 + this.rng.nextDouble() * 0.6;

         for(double[] q : plan) {
            double[] w = this.local(cx, cz, rot, q[0], q[1]);
            int kind = (int)q[4];
            this.commit(w[0], w[1], q[2], q[3], rot);
            String code = this.nextCode(kind == 3 ? "f_wall" : "f_house");
            String owner = homeName == null ? "" : homeName + "·";
            String var10000;
            switch (kind) {
               case 0 -> var10000 = homeName != null ? homeName : nameOr(home, cx, cz);
               case 1 -> var10000 = owner + (q[0] > (double)0.0F ? "东厢房" : "西厢房");
               case 2 -> var10000 = "院门";
               default -> var10000 = null;
            }

            String name = var10000;
            String semantic = "{\"compound\":true,\"part\":" + kind + (homeRef == null ? "" : ",\"homeRef\":\"" + homeRef + "\",\"anchor\":\"" + (anchor == null ? "" : anchor.replace("\"", "")) + "\"") + "}";
            double h;
            int floors;
            String shape;
            String kindCode;
            int residency;
            switch (kind) {
               case 0:
                  h = floorsH * (double)(1 + this.rng.nextInt(2));
                  floors = (int)Math.max(1L, Math.round(h / floorsH));
                  shape = home.roofShape();
                  kindCode = "building";
                  residency = 1;
                  break;
               case 1:
                  h = floorsH;
                  floors = 1;
                  shape = home.roofShape();
                  kindCode = "building";
                  residency = 1;
                  break;
               case 2:
                  h = 3.6;
                  floors = 1;
                  shape = "gable";
                  kindCode = "building";
                  residency = 0;
                  break;
               default:
                  h = 2.3 + this.rng.nextDouble() * 0.4;
                  floors = 1;
                  shape = "wall";
                  kindCode = "wall";
                  residency = 0;
            }

            double[] door = this.local(cx, cz, rot, q[0], q[1] + q[3] / (double)2.0F);
            this.features.add(new PlacedFeature(code, name, "民居", kindCode, kind == 3 ? null : home.code(), w[0], w[1], q[2], q[3], rot, h, floors, this.elevationAt(w[0], w[1]), wall, roof, shape, door[0], door[1], rot, this.rect(w[0], w[1], q[2], q[3], rot), semantic, (String)null, residency, kind == 0 ? 2 : 1));
            if (kind == 0 && homeRef != null) {
               double[] mainDoor = this.local(cx, cz, rot, q[0], q[1] + q[3] / (double)2.0F);
               this.dwellingSites.put(homeRef, new DwellingSite(homeRef, name, code, w[0], w[1], mainDoor[0], mainDoor[1], anchor == null ? "" : anchor, 0));
            }

            codes.add(code);
         }

         String parcelCode = this.nextCode("parcel_court");
         this.parcels.add(new PlacedParcel(parcelCode, "homestead", cx, cz, 283.86000000000007, (double)this.t.buildable[Math.max(0, this.cellIndex(cx, cz))], this.rect(cx, cz, (double)17.0F, 17.4, rot), road.code(), (String)codes.get(0)));
         this.groups.add(new PlacedGroup(this.nextCode("grp_court"), "三合院", "courtyard", cx, cz, 9.2, rot, home.code(), "正屋坐北、东西厢房、院门朝巷"));
         ++this.builtDwellings;
         this.builtOutbuildings += 2;
         return true;
      }
   }

   private double[] local(double cx, double cz, double rotDeg, double lx, double lz) {
      double rad = Math.toRadians(rotDeg);
      double cos = Math.cos(rad);
      double sin = Math.sin(rad);
      return new double[]{cx + lx * cos - lz * sin, cz + lx * sin + lz * cos};
   }

   private AssetSpec pickByCategory(List<AssetSpec> assets, String category) {
      List<AssetSpec> pool = new ArrayList();

      for(AssetSpec a : assets) {
         if ("building".equals(a.kind()) && category.equals(a.category())) {
            pool.add(a);
         }
      }

      if (pool.isEmpty()) {
         for(AssetSpec a : assets) {
            if ("building".equals(a.kind())) {
               pool.add(a);
            }
         }
      }

      return pool.isEmpty() ? null : (AssetSpec)pool.get(this.rng.nextInt(pool.size()));
   }

   private void placeAlongRoads(List<RoadSpec> roads, List<AssetSpec> assets, Map<String, MapSites.Site> sites) {
      if (!assets.isEmpty()) {
         MapSites.Site core = (MapSites.Site)sites.get("core");
         double coreX = core == null ? (double)this.size * (double)0.5F * this.mpc : core.x();
         double coreZ = core == null ? (double)this.size * (double)0.5F * this.mpc : core.z();
         double mapSpan = (double)this.size * this.mpc;
         List<RoadSpec> ordered = new ArrayList(roads);
         ordered.sort((a, b) -> {
            // 骨架先排、目的地路后排：街墙是由「街」决定的，先让正街两条边排满，
            // 再让外部路去填镇口剩下的空位，否则码头路会把正街边上的好位置先占掉。
            boolean sa = isSkeleton(a);
            boolean sb = isSkeleton(b);
            if (sa != sb) {
               return sa ? -1 : 1;
            }
            return Integer.compare(b.grade(), a.grade());
         });

         for(RoadSpec road : ordered) {
            if (this.budgetFull()) {
               break;
            }

            if (road.pathMeters().size() >= 2 && !"pier".equals(road.kind())) {
               this.placeOnRoad(road, assets, coreX, coreZ, mapSpan);
            }
         }

      }
   }

   /**
    * 是否 S3 街巷骨架（正街/后街/横巷/环巷/胡同）。
    *
    * <p>骨架与「AI 目的地路」的区别决定两件事：① 骨架两侧排的是连续街墙（固定退线、几乎贴着）；
    * ② 目的地路只负责把人带到镇口，镇外不许再沿路撒房。两者的 code 前缀由 {@code StreetNetwork}
    * 统一给出，这里按前缀识别——不去猜 kind/grade，因为 AI 给的目的地路同样可能叫 "street"/"lane"。</p>
    *
    * @param r 道路
    * @return true=骨架街巷
    */
   private static boolean isSkeleton(RoadSpec r) {
      String code = r.code() == null ? "" : r.code();
      return code.startsWith("main_street") || code.startsWith("back_lane")
              || code.startsWith("cross_lane") || code.startsWith("ring_")
              || code.startsWith("alley_");
   }

   /**
    * 「连接型道路」：存在理由就是<b>把两块接起来</b>，不参与去重与剪枝。
    *
    * <p>包含三类：连通性连接路（{@code conn_} / {@code farm_conn_}）、码头引道（{@code wharf_}）。
    * 它们的共同点是天生贴着别的路走、天生很短、落点常常不是人家——按普通道路的剪枝口径判，
    * 会把连接本身剪断（路网散成几块）或者把码头引道当成"没人用的尾巴"删掉。</p>
    *
    * @param code 道路编码
    * @return true=豁免去重与剪枝
    */
   private static boolean isLinkRoad(String code) {
      return code != null && (code.startsWith("conn_") || code.startsWith("farm_conn_")
              || code.startsWith("wharf_"));
   }

   private void placeOnRoad(RoadSpec road, List<AssetSpec> assets, double coreX, double coreZ, double mapSpan) {
      List<double[]> path = road.pathMeters();
      boolean major = road.grade() >= 2;
      boolean alley = "alley".equals(road.kind());
      boolean wall = isSkeleton(road);
      double[] cum = arcLengths(path);
      double total = cum[cum.length - 1];
      if (!(total < (double)4.0F)) {
         for(int side = -1; side <= 1; side += 2) {
            double s = (double)0.0F;

            while(s < total && !this.budgetFull()) {
               double[] p = pointAt(path, cum, s);
               double[] tan = chordDir(path, cum, s, (double)7.0F);
               if (tan == null) {
                  break;
               }

               double[] used = this.tryPlaceOne(p[0], p[1], -tan[1] * (double)side, tan[0] * (double)side, tan[0], tan[1], road, assets, coreX, coreZ, mapSpan, major, alley, wall);
               if (used == null) {
                  ++s;
               } else {
                  double half = Math.max((double)0.0F, used[0] - 0.6) / (double)2.0F;
                  if (used[0] >= (double)3.0F) {
                     this.frontages.add(new Frontage(road.code(), side, s - half, s + half, used[2]));
                  }

                  s += used[0] + used[1];
               }
            }
         }

      }
   }

   private DwellingSite nearestDwelling(double x, double z, double radius) {
      DwellingSite best = null;
      double bestD = radius;

      for(DwellingSite s : this.dwellingSites.values()) {
         double d = Math.hypot(s.x() - x, s.z() - z);
         if (d < bestD) {
            bestD = d;
            best = s;
         }
      }

      return best;
   }

   private static String ownerSemantic(DwellingSite owner) {
      return owner == null ? "" : ",\"owner\":\"" + owner.homeRef() + "\"";
   }

   /**
    * 屋边菜畦：<b>只长在自家院墙外 6~14 米</b>。
    *
    * <p><b>为什么必须贴着自己家</b>：菜畦是「人家门口那一小片」，不是大田。旧实现把菜畦当普通田块随机撒，
    * 结果 68 块菜畦散在田野里，图上糊成一片「黄绿碎纸」。真实聚落里，菜畦就在院墙外、
    * 篱笆边、井台旁——它属于某一户，也由那一户每天去浇。</p>
    *
    * <p>产出：8~20 ㎡ 的小块深湿土 + 几行细绿，语义带 {@code garden:true, owner:homeRef, crop}——
    * 日程里「在自家菜畦浇菜」就靠这个 owner 找到地方。</p>
    *
    * @param assets 资产类型库
    */
   private void placeKitchenGardens(List<AssetSpec> assets) {
      AssetSpec field = null;

      for(AssetSpec a : assets) {
         if ("field".equals(a.kind())) {
            field = a;
            break;
         }
      }

      String[] crops = new String[]{"vegetable", "vegetable", "beans", "rapeseed", "cabbage"};
      String[] soil = new String[]{"#5d4f34", "#554830", "#645537", "#5a4c32", "#695b3d"};
      int cap = this.budget == null ? 24 : Math.min(40, Math.max(8, this.budget.households() * 2));
      int made = 0;

      for(DwellingSite site : this.dwellingSites.values()) {
         if (made >= cap) {
            break;
         }

         int want = site.members() >= 4 ? 2 : 1;

         for(int k = 0; k < want && made < cap; ++k) {
            if (this.placeGardenNear(site, field, crops, soil)) {
               ++made;
            }
         }
      }

      if (made < cap && !this.dwellingSites.isEmpty()) {
         double step = (double)7.5F;

         for(double gx = this.settleX - this.townRadius; gx <= this.settleX + this.townRadius && made < cap; gx += step) {
            for(double gz = this.settleZ - this.townRadius; gz <= this.settleZ + this.townRadius && made < cap; gz += step) {
               if (!(Math.hypot(gx - this.settleX, gz - this.settleZ) > this.townRadius * 0.95)) {
                  double x = gx + this.rng.nextDouble(-2.2, 2.2);
                  double z = gz + this.rng.nextDouble(-2.2, 2.2);
                  int i = this.cellIndex(x, z);
                  if (i >= 0 && !((double)this.t.buildable[i] > 0.28) && this.nearestDwelling(x, z, (double)16.0F) != null && this.tryGardenRect(x, z, this.rng.nextDouble((double)0.0F, (double)90.0F), field, crops, soil, (DwellingSite)null)) {
                     ++made;
                  }
               }
            }
         }
      }

      this.diag.put("菜畦", made);
      if (!this.dwellingSites.isEmpty()) {
         this.diag.put("菜畦户均×10", (int)Math.round((double)made * (double)10.0F / (double)this.dwellingSites.size()));
      }

   }

   private boolean placeGardenNear(DwellingSite site, AssetSpec field, String[] crops, String[] soil) {
      double[] dists = new double[]{(double)7.5F, (double)10.5F, (double)13.5F};

      for(double dist : dists) {
         for(int k = 0; k < 8; ++k) {
            double ang = (Math.PI * 2D) * (double)k / (double)8.0F + this.rng.nextDouble(-0.2, 0.2);
            double x = site.x() + Math.cos(ang) * dist;
            double z = site.z() + Math.sin(ang) * dist;
            int i = this.cellIndex(x, z);
            if (i >= 0 && !((double)this.t.buildable[i] > 0.3)) {
               double rot = Math.toDegrees(ang) + (double)90.0F;
               if (this.tryGardenRect(x, z, rot, field, crops, soil, site)) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private boolean tryGardenRect(double x, double z, double rot, AssetSpec field, String[] crops, String[] soil, DwellingSite owner) {
      double w = (double)4.0F + this.rng.nextDouble() * 1.4;
      double d = 2.4 + this.rng.nextDouble() * 1.2;
      double[][] poly = this.rect(x, z, d, w, rot);
      if (this.blockedPolyForFarm(poly) != null) {
         return false;
      } else if (!this.canPlace(x, z, d, w, rot, 0.0F)) {
         return false;
      } else {
         this.commit(x, z, d, w, rot);
         int ci = this.rng.nextInt(crops.length);
         String ownerRef = owner == null ? "" : owner.homeRef();
         this.features.add(new PlacedFeature(this.nextCode("f_garden"), owner == null ? "菜畦" : owner.homeName() + "·菜畦", "生产", "field", field == null ? null : field.code(), x, z, d, w, rot, 0.18, 1, this.elevationAt(x, z), (String)null, soil[ci], (String)null, x, z, rot, poly, "{\"crop\":\"" + crops[ci] + "\",\"garden\":true,\"ridge\":true" + (ownerRef.isEmpty() ? "" : ",\"owner\":\"" + ownerRef + "\"") + "}", (String)null, 0, 1));
         this.parcels.add(new PlacedParcel(this.nextCode("parcel_garden"), "garden", x, z, polyArea(poly), (double)this.t.buildable[Math.max(0, this.cellIndex(x, z))], poly, (String)null, (String)null));
         return true;
      }
   }

   private void sealFrontages(List<RoadSpec> roads) {
      Map<String, Map<String, List<Frontage>>> byRoadSide = new HashMap();

      for(Frontage f : this.frontages) {
         ((List)((Map)byRoadSide.computeIfAbsent(f.road(), (k) -> new HashMap())).computeIfAbsent(f.side() > 0 ? "R" : "L", (k) -> new ArrayList())).add(f);
      }

      for(RoadSpec road : roads) {
         Map<String, List<Frontage>> sides = (Map)byRoadSide.get(road.code());
         if (sides != null) {
            List<double[]> path = road.pathMeters();
            if (path.size() >= 2) {
               double[] cum = arcLengths(path);
               double total = cum[cum.length - 1];

               for(Map.Entry<String, List<Frontage>> e : sides.entrySet()) {
                  int side = "R".equals(e.getKey()) ? 1 : -1;
                  List<Frontage> runs = new ArrayList((Collection)e.getValue());
                  runs.sort(Comparator.comparingDouble(Frontage::s0));

                  for(int i = 1; i < runs.size(); ++i) {
                     Frontage a = (Frontage)runs.get(i - 1);
                     Frontage b = (Frontage)runs.get(i);
                     double gap = b.s0() - a.s1();
                     if (!(gap < (double)2.5F) && !(gap > (double)11.0F) && !(this.rng.nextDouble() < 0.34)) {
                        double s0 = Math.min(total, Math.max((double)0.0F, a.s1() + (double)0.25F));
                        double s1 = Math.min(total, Math.max((double)0.0F, b.s0() - (double)0.25F));
                        if (!(s1 - s0 < 1.6)) {
                           double off = Math.min(a.wallOffset(), b.wallOffset());
                           double[] mid = pointAt(path, cum, (s0 + s1) / (double)2.0F);
                           double[] tan = chordDir(path, cum, (s0 + s1) / (double)2.0F, (double)7.0F);
                           if (tan != null) {
                              double nx = -tan[1] * (double)side;
                              double nz = tan[0] * (double)side;
                              double[] p0 = pointAt(path, cum, s0);
                              double[] p1 = pointAt(path, cum, s1);
                              double x0 = p0[0] + nx * off;
                              double z0 = p0[1] + nz * off;
                              double x1 = p1[0] + nx * off;
                              double z1 = p1[1] + nz * off;
                              double len = Math.hypot(x1 - x0, z1 - z0);
                              if (!(len < 1.2)) {
                                 double cx = (x0 + x1) / (double)2.0F;
                                 double cz = (z0 + z1) / (double)2.0F;
                                 double rot = Math.toDegrees(Math.atan2(z1 - z0, x1 - x0));
                                 double thickness = 0.34 + this.rng.nextDouble() * 0.14;
                                 if (!this.canPlace(cx, cz, len, thickness, rot)) {
                                    this.bump("墙:放不下");
                                 } else {
                                    this.commit(cx, cz, len, thickness, rot);
                                    this.features.add(new PlacedFeature(this.nextCode("f_wall"), "院墙", "民居", "wall", (String)null, cx, cz, len, thickness, rot, 2.2, 1, this.elevationAt(cx, cz), (String)null, (String)null, "wall", mid[0], mid[1], rot, this.rect(cx, cz, len, thickness, rot), "{\"yardWall\":true}", (String)null, 0, 1));
                                    this.bump("院墙");
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }

   }

   private static double[] arcLengths(List<double[]> path) {
      double[] cum = new double[path.size()];

      for(int i = 1; i < path.size(); ++i) {
         cum[i] = cum[i - 1] + Math.hypot(((double[])path.get(i))[0] - ((double[])path.get(i - 1))[0], ((double[])path.get(i))[1] - ((double[])path.get(i - 1))[1]);
      }

      return cum;
   }

   private static double[] chordDir(List<double[]> path, double[] cum, double s, double half) {
      if (path.size() < 2) {
         return null;
      } else {
         double[] a = pointAt(path, cum, Math.max((double)0.0F, s - half));
         double[] b = pointAt(path, cum, Math.min(cum[cum.length - 1], s + half));
         double vx = b[0] - a[0];
         double vz = b[1] - a[1];
         double len = Math.hypot(vx, vz);
         return len < 1.0E-6 ? null : new double[]{vx / len, vz / len};
      }
   }

   private static double[] pointAt(List<double[]> path, double[] cum, double s) {
      int n = path.size();
      if (s <= (double)0.0F) {
         return (double[])path.get(0);
      } else if (s >= cum[n - 1]) {
         return (double[])path.get(n - 1);
      } else {
         int lo = 0;
         int hi = n - 1;

         while(lo < hi - 1) {
            int mid = lo + hi >>> 1;
            if (cum[mid] <= s) {
               lo = mid;
            } else {
               hi = mid;
            }
         }

         double segLen = cum[hi] - cum[lo];
         if (segLen < 1.0E-9) {
            return (double[])path.get(lo);
         } else {
            double f = (s - cum[lo]) / segLen;
            double[] a = (double[])path.get(lo);
            double[] b = (double[])path.get(hi);
            return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
         }
      }
   }

   private double[] tryPlaceOne(double px, double pz, double nx, double nz, double ux, double uz, RoadSpec road, List<AssetSpec> assets, double coreX, double coreZ, double mapSpan, boolean major, boolean alley, boolean wall) {
      if (this.budgetFull()) {
         return null;
      } else {
         double distCore = Math.hypot(px - coreX, pz - coreZ);
         double edge = distCore / this.townRadius;
         if (wall) {
            // 街墙只排到镇界：正街/后街/横巷本来就只长在镇里，出镇就不是"街"了
            if (edge > 1.05) {
               this.bump("镇外");
               return null;
            }
         } else {
            // 沿<外部路>（码头路/田区路/林道）排房必须收口到镇界。
            // 旧口径是 edge>1.18 才拒绝、0.55 之后按概率衰减——68 米的小镇，97 米内都在撒房，
            // 图上就是「一条路伸进林子，尽头还撒着几栋房子」：这是用户点名的杂乱。
            // 镇外的零星人家只允许由 capRoadEnds 生成一处成组的小屯。
            if (edge > 1.02) {
               this.bump("镇外");
               return null;
            }
            if (edge > 0.86 && this.rng.nextDouble() < (edge - 0.86) / 0.16) {
               this.bump("镇边");
               return null;
            }
         }
         {
            AssetSpec spec = this.pickSpec(assets, distCore / mapSpan, major, distCore < (double)45.0F, alley);
            this.bump("尝试");
            if (spec == null) {
               this.bump("无类型");
               return null;
            } else {
               // 街墙的尺度要"统一"：真实一条街上的房子面宽差不了 10%（都是同一个木匠按同一套料做的），
               // 而旧口径 0.88~1.16 的随机宽度会让同一条街的房子看起来像随手摆的盒子。
               double baseW = spec.baseW() * (wall ? 0.94 + this.rng.nextDouble() * 0.12
                       : 0.88 + this.rng.nextDouble() * 0.28);
               double baseD = spec.baseD() * (wall ? 0.94 + this.rng.nextDouble() * 0.10
                       : 0.88 + this.rng.nextDouble() * 0.28);
               if (alley) {
                  baseW *= 0.78;
                  baseD *= 0.85;
               }

               // 街墙靠的是「几乎贴着」：退线固定 2.0~2.6 米、户间 0.6~1.6 米。
               // 旧口径退线 0.9~2.7、间隙 0.8~6.4 随机跳，读起来是"散落的盒子"。
               double setback = wall ? 2.0 + this.rng.nextDouble() * 0.6
                       : 1.4 * (0.65 + this.rng.nextDouble() * 1.3);
               double gap = wall ? 0.6 + this.rng.nextDouble() * 1.0
                       : (this.rng.nextDouble() < 0.16 ? 3.2 + this.rng.nextDouble() * 3.2
                       : 0.8 + this.rng.nextDouble() * 1.6);
               // 房屋朝向：<b>长边（立面）必须与道路平行</b>。
               // 旧写法 atan2(-nx,-nz) 把"指向路心"的法线当成了长边方向，于是房子与街道成 30~45°
               // 的斜角——整条街看上去像被随机旋转过的盒子（用户点名的"朝向不真实"）。
               // 长边应沿路走（切线 ux,uz），进深才沿法线；门仍然朝路（doorDir 保持不变）。
               double rot = Math.toDegrees(Math.atan2(uz, ux));
               double[][] tries = new double[][]{{(double)1.0F, (double)1.0F, (double)1.0F}, {0.94, 0.97, (double)0.5F}, {0.74, 0.82, 0.28}};
               double cx = (double)0.0F;
               double cz = (double)0.0F;
               double w = (double)0.0F;
               double d = (double)0.0F;
               double wallOffset = (double)0.0F;
               boolean ok = false;

               for(double[] tr : tries) {
                  w = baseW * tr[0];
                  d = baseD * tr[1];
                  wallOffset = road.width() / (double)2.0F + setback * tr[2];
                  double offset = wallOffset + d / (double)2.0F;
                  cx = px + nx * offset;
                  cz = pz + nz * offset;
                  if (this.canPlace(cx, cz, w, d, rot)) {
                     ok = true;
                     break;
                  }
                  // 被一棵树/一块石头挡住时，不后退成一个大缺口，而是沿街平移半个开间再试：
                  // 街墙的连续性是"贴着"，不是"每户都从同一个模子刻出来"。
                  if (wall && tr[0] > 0.99) {
                     for(double shift : new double[]{1.6, -1.6, 3.2, -3.2}) {
                        double tx = cx + ux * shift;
                        double tz = cz + uz * shift;
                        if (this.canPlace(tx, tz, w, d, rot)) {
                           cx = tx;
                           cz = tz;
                           ok = true;
                           break;
                        }
                     }
                     if (ok) {
                        break;
                     }
                  }
               }

               if (!ok) {
                  return null;
               } else {
                  this.commit(cx, cz, w, d, rot);
                  String code = this.nextCode("f_" + spec.code());
                  int floors = spec.floorsMin() + (spec.floorsMax() > spec.floorsMin() ? this.rng.nextInt(spec.floorsMax() - spec.floorsMin() + 1) : 0);
                  double height = (double)floors * (3.1 + this.rng.nextDouble() * 0.8) + (this.rng.nextDouble() < (double)0.5F ? 0.8 : (double)0.0F);
                  double[][] poly = this.rect(cx, cz, w, d, rot);
                  double doorX = cx - nx * (d / (double)2.0F);
                  double doorZ = cz - nz * (d / (double)2.0F);
                  double doorDir = Math.toDegrees(Math.atan2(-nx, -nz));
                  String name = this.maybeName(spec, cx, cz);
                  DwellingSite owner = this.nearestDwelling(cx, cz, (double)26.0F);
                  String ownerPrefix = owner != null && owner.homeName() != null ? owner.homeName() + "·" : "";
                  List var10000 = this.features;
                  String var10005 = spec.category();
                  String var10007 = spec.code();
                  double var10015 = this.elevationAt(cx, cz);
                  String var10016 = spec.wallColor();
                  String var10017 = spec.roofColor();
                  String var10018 = spec.roofShape();
                  String var10023 = road.code();
                  var10000.add(new PlacedFeature(code, name, var10005, "building", var10007, cx, cz, w, d, rot, height, floors, var10015, var10016, var10017, var10018, doorX, doorZ, doorDir, poly, "{\"road\":\"" + var10023 + "\",\"side\":\"" + (nx > (double)0.0F ? "east" : "west") + "\"}", (String)null, "民居".equals(spec.category()) ? 1 : 0, "公共".equals(spec.category()) ? 3 : 1));
                  if ("民居".equals(spec.category())) {
                     ++this.builtDwellings;
                  }

                  String parcelCode = this.nextCode("parcel");
                  this.parcels.add(new PlacedParcel(parcelCode, "homestead", cx, cz, w * d + (double)6.0F, (double)this.t.buildable[this.cellIndex(cx, cz)], poly, road.code(), code));
                  boolean home = "民居".equals(spec.category());
                  if (!(this.rng.nextDouble() < (home ? 0.74 : 0.4)) || home && this.outbuildingsFull()) {
                     if ("公共".equals(spec.category()) || "商铺".equals(spec.category())) {
                        this.groups.add(new PlacedGroup(this.nextCode("grp"), name, "block", cx, cz, Math.max(w, d) / (double)2.0F, rot, spec.code(), spec.category()));
                     }
                  } else {
                     double bw = w * (home ? 0.6 + this.rng.nextDouble() * 0.24 : 0.48 + this.rng.nextDouble() * 0.26);
                     double bd = d * (0.7 + this.rng.nextDouble() * 0.24);
                     double backGap = 2.2 + this.rng.nextDouble() * 2.8;
                     double bx = cx + nx * (d / (double)2.0F + backGap + bd / (double)2.0F);
                     double bz = cz + nz * (d / (double)2.0F + backGap + bd / (double)2.0F);
                     boolean placedBack = false;
                     if (this.canPlace(bx, bz, bw, bd, rot)) {
                        this.commit(bx, bz, bw, bd, rot);
                        String rear = this.nextCode("f_rear");
                        var10000 = this.features;
                        String var10004 = ownerPrefix + "厢房";
                        var10005 = spec.category();
                        var10007 = spec.code();
                        double var10013 = Math.max(2.6, height * 0.62);
                        int var10014 = Math.max(1, floors - 1);
                        var10015 = this.elevationAt(bx, bz);
                        var10016 = spec.wallColor();
                        var10017 = rearRoof(spec.roofColor());
                        double var10019 = bx - nx * (bd / (double)2.0F);
                        double var10020 = bz - nz * (bd / (double)2.0F);
                        double[][] var10022 = this.rect(bx, bz, bw, bd, rot);
                        var10023 = ownerSemantic(owner);
                        var10000.add(new PlacedFeature(rear, var10004, var10005, "building", var10007, bx, bz, bw, bd, rot, var10013, var10014, var10015, var10016, var10017, "gable", var10019, var10020, doorDir, var10022, "{\"rear\":true" + var10023 + "}", (String)null, home ? 1 : 0, 1));
                        this.parcels.add(new PlacedParcel(this.nextCode("parcel_rear"), "homestead", bx, bz, bw * bd + (double)4.0F, (double)this.t.buildable[Math.max(0, this.cellIndex(bx, bz))], this.rect(bx, bz, bw, bd, rot), road.code(), rear));
                        double gx = (cx + bx) / (double)2.0F;
                        double gz = (cz + bz) / (double)2.0F;
                        double gr = Math.hypot(cx - bx, cz - bz) / (double)2.0F + Math.max(w, d) / (double)2.0F;
                        this.groups.add(new PlacedGroup(this.nextCode("grp"), name, "courtyard", gx, gz, gr, rot, spec.code(), home ? "两进院：临街房 + 后院" : "前店后场"));
                        if (home) {
                           ++this.builtOutbuildings;
                        }

                        placedBack = true;
                     }

                     if (!placedBack && home) {
                        double sw = w * 0.4;
                        double sd = d * 0.38;
                        double sx = cx + nx * (d / (double)2.0F + 1.4 + sd / (double)2.0F);
                        double sz = cz + nz * (d / (double)2.0F + 1.4 + sd / (double)2.0F);
                        if (this.canPlace(sx, sz, sw, sd, rot)) {
                           this.commit(sx, sz, sw, sd, rot);
                           this.features.add(new PlacedFeature(this.nextCode("f_shed"), ownerPrefix + "柴房", spec.category(), "building", spec.code(), sx, sz, sw, sd, rot, 2.3, 1, this.elevationAt(sx, sz), spec.wallColor(), rearRoof(spec.roofColor()), "shed", sx - nx * (sd / (double)2.0F), sz - nz * (sd / (double)2.0F), doorDir, this.rect(sx, sz, sw, sd, rot), "{\"annex\":true" + ownerSemantic(owner) + "}", (String)null, 0, 1));
                           ++this.builtOutbuildings;
                        }
                     }
                  }

                  return new double[]{w + 0.6, gap, wallOffset};
               }
            }
         }
      }
   }

   private void infillBlocks(List<RoadSpec> roads, List<AssetSpec> assets, double coreX, double coreZ) {
      AssetSpec home = this.pickByCategory(assets, "民居");
      if (home != null) {
         int stepCells = Math.max(3, (int)Math.round((double)4.5F / this.mpc));
         int rad = Math.max(5, (int)Math.round((double)13.0F / this.mpc));
         int made = 0;
         int maxInfill = 240;
         if (this.budget != null) {
            maxInfill = Math.max(0, this.budget.total() - this.countBuilt());
         }

         for(int gz = 1; gz < this.size - 1 && made < maxInfill; gz += stepCells) {
            for(int gx = 1; gx < this.size - 1 && made < maxInfill; gx += stepCells) {
               double wx = (double)gx * this.mpc;
               double wz = (double)gz * this.mpc;
               if (!(Math.hypot(wx - coreX, wz - coreZ) > this.townRadius * 0.95)) {
                  int i = gz * this.size + gx;
                  if (!(this.occ[i] > 0.05F) && !((double)this.t.height[i] <= this.t.seaLevel + 0.004) && !this.t.riverMask[i] && !(this.t.buildable[i] < 0.12F) && !(this.rng.nextDouble() > 0.7)) {
                     int built = 0;
                     int total = 0;

                     for(int dz = -rad; dz <= rad; dz += 2) {
                        for(int dx = -rad; dx <= rad; dx += 2) {
                           int x = gx + dx;
                           int z = gz + dz;
                           if (x >= 0 && z >= 0 && x < this.size && z < this.size) {
                              ++total;
                              if (this.occ[z * this.size + x] > 0.05F) {
                                 ++built;
                              }
                           }
                        }
                     }

                     if (total != 0 && !((double)built / (double)total < 0.24)) {
                        double[] rd = this.nearestRoadDir(roads, wx, wz);
                        if (rd != null && !(rd[2] > (double)24.0F)) {
                           double w = 5.2 + this.rng.nextDouble() * 3.2;
                           double d = 4.6 + this.rng.nextDouble() * 2.6;
                           // 填空的房子也要立面平行道路：长边沿切线 rd=(ux,uz)，旧写法把法线当长边
                           double rot = Math.toDegrees(Math.atan2(rd[1], rd[0]));
                           if (this.canPlace(wx, wz, w, d, rot)) {
                              this.commit(wx, wz, w, d, rot);
                              double[] door = this.local(wx, wz, rot, (double)0.0F, d / (double)2.0F);
                              boolean dwelling = this.rng.nextDouble() < 0.45;
                              String code = this.nextCode("f_infill");
                              this.features.add(new PlacedFeature(code, genericBuildingName("民居", wx, wz), "民居", "building", home.code(), wx, wz, w, d, rot, dwelling ? 3.2 : 2.4, 1, this.elevationAt(wx, wz), home.wallColor(), home.roofColor(), dwelling ? home.roofShape() : "shed", door[0], door[1], rot, this.rect(wx, wz, w, d, rot), "{\"infill\":true}", (String)null, dwelling ? 1 : 0, 1));
                              this.parcels.add(new PlacedParcel(this.nextCode("parcel_infill"), "homestead", wx, wz, w * d, (double)this.t.buildable[i], this.rect(wx, wz, w, d, rot), (String)null, code));
                              ++made;
                           }
                        }
                     }
                  }
               }
            }
         }

         if (made > 0) {
            this.diag.merge("填空", made, Integer::sum);
         }

      }
   }

   private double[] nearestRoadDir(List<RoadSpec> roads, double x, double z) {
      double best = Double.MAX_VALUE;
      double ux = (double)0.0F;
      double uz = (double)0.0F;

      for(RoadSpec r : roads) {
         if (!"pier".equals(r.kind())) {
            List<double[]> path = r.pathMeters();

            for(int i = 1; i < path.size(); ++i) {
               double[] a = (double[])path.get(i - 1);
               double[] b = (double[])path.get(i);
               double vx = b[0] - a[0];
               double vz = b[1] - a[1];
               double vv = vx * vx + vz * vz;
               if (!(vv < 1.0E-9)) {
                  double t = ((x - a[0]) * vx + (z - a[1]) * vz) / vv;
                  t = Math.max((double)0.0F, Math.min((double)1.0F, t));
                  double dx = x - (a[0] + vx * t);
                  double dz = z - (a[1] + vz * t);
                  double dd = Math.hypot(dx, dz);
                  if (dd < best) {
                     best = dd;
                     double len = Math.sqrt(vv);
                     ux = vx / len;
                     uz = vz / len;
                  }
               }
            }
         }
      }

      if (best == Double.MAX_VALUE) {
         return null;
      } else {
         return new double[]{ux, uz, best};
      }
   }

   /**
    * 农田：<b>绕着村口铺开、每片贴着一条路、按户切成条田</b>。
    *
    * <p>三件事决定了田像不像真的：</p>
    * <ol>
    *   <li><b>分散</b>：真实村镇的地是绕着聚落几面铺的（东南缓坡一片、西北高地一片），
    *       而不是全堆在同一侧的同一条带里；</li>
    *   <li><b>挨着路</b>：每片田都从最近的一条村道「长」出去，人下地、粮回仓走的就是那条路；</li>
    *   <li><b>按户切条</b>：一条 6 米宽的窄长垄归一户，一户几条、散在各段（轮作与地力公平），
    *       归属写进语义 {@code owner}，日程里「下自家田」才有落点。</li>
    * </ol>
    *
    * <p>数量口径：{@code 户数 × 2.5} 条（12 户 → 30 条），每条约 205 ㎡——够养活 36 口人，
    * 也不会把岛铺成一张黄纸。</p>
    *
    * @param assets 资产类型库
    * @param sites  语义落位点
    * @param net    路网（会把田头路/下地土路加进去）
    */
   private void placeFarmlands(List<AssetSpec> assets, Map<String, MapSites.Site> sites, List<RoadSpec> net) {
      AssetSpec field = this.findKind(assets, "field");
      int pop;
      if (this.budget != null) {
         pop = Math.max(1, this.budget.residents());
      } else {
         int houses = 0;

         for(PlacedFeature f : this.features) {
            houses += f.residency();
         }

         pop = houses * 3;
      }

      int households = this.budget == null ? Math.max(1, pop / 3) : Math.max(1, this.budget.households());
      int target = Math.max(8, Math.min(320, households * 2 + households / 2));
      this.diag.put("人口", pop);
      this.diag.put("农户条田", target);
      float[] cost = this.costWithClearance();
      float[] built = this.builtOccupancy();
      List<Double> d2 = new ArrayList();

      for(PlacedFeature f : this.features) {
         if ("building".equals(f.kind())) {
            d2.add(Math.hypot(f.x() - this.settleX, f.z() - this.settleZ));
         }
      }

      double builtR = (double)60.0F;
      if (!d2.isEmpty()) {
         d2.sort(Double::compare);
         builtR = Math.max((double)60.0F, (Double)d2.get(Math.min(d2.size() - 1, (int)((double)d2.size() * 0.8))));
      }

      double fieldStart = Math.min(this.townRadius * 0.95, builtR + (double)12.0F);
      this.diag.put("田区起点", (int)Math.round(fieldStart));
      // 农区方位：要多方向，而且每一片都得挨着一条能走车的路。
      // 旧口径只取两个方位、每片又是一大块矩形网格，结果 30 条田全挤在镇子同一侧的同一条带里，
      // 远看就是一摞码好的木板——真实村镇的地是绕着村口铺开的：东南一片、西北一片，各带一条田头路。
      double[][] dirs = this.farmDirections(6, fieldStart);
      this.diag.put("农区方位", dirs.length);
      int made = 0;

      for(int round = 0; round < 4 && made < target; ++round) {
         for(double[] d : dirs) {
            if (made >= target) {
               break;
            }

            // 每一侧给一份「整块矩阵」的量（一次 12~18 块），不要拆成每轮 6 块的小条：
            // 按 6 块分轮铺出来的是"一排 6 个方格"，不是用户要的"一块一块挨着的棋盘"。
            // 目标 30 块时正好是"一片 6×3 + 一片 6×2"，也就是村子两面的两大片农区。
            int share = Math.min(18, target - made);
            made += this.farmBlock(d[0], share, field, net, cost, built, fieldStart + (double)(round * 22));
         }
      }

      this.diag.merge("农田", made, Integer::sum);
      // 补扫一：沿「已有出村路」采样方位再铺一轮。
      // 方位法只覆盖「从镇中心射出去还正好有路」的方向；真实村镇的田是沿着每一条出村路
      // 两侧铺开的——路往哪拐，田就跟到哪。这里把每条路上镇外的点折成方位角再交给 farmBlock：
      // farmBlock 自己会在这个方位上找落脚点，于是「路拐了个弯」的方向也能种上田。
      if (made < target) {
         List<Double> extra = new ArrayList();

         for(RoadSpec r : net) {
            List<double[]> path = r.pathMeters();
            if (r.grade() >= 1 && path != null && path.size() >= 2) {
               double[] cum = arcLengths(path);
               double total = cum[cum.length - 1];

               for(double s = 6.0; s < total - 6.0; s += 14.0) {
                  double[] pt = pointAt(path, cum, s);
                  double d = Math.hypot(pt[0] - this.settleX, pt[1] - this.settleZ);
                  if (!(d < fieldStart * 0.9) && !(d > fieldStart + 90.0)) {
                     extra.add(Math.atan2(pt[1] - this.settleZ, pt[0] - this.settleX));
                  }
               }
            }
         }

         List<Double> picked = new ArrayList();

         for(double a : extra) {
            boolean apart = true;

            for(double b : picked) {
               if (Math.abs(angDiff(a, b)) < Math.toRadians((double)40.0F)) {
                  apart = false;
                  break;
               }
            }

            if (apart) {
               picked.add(a);
            }
         }

         this.diag.put("农区补扫方位", picked.size());

         for(double a : picked) {
            if (made >= target) {
               break;
            }

            made += this.farmBlock(a, Math.min(18, target - made), field, net, cost, built, fieldStart);
         }

         this.diag.put("农田", made);
      }

      // 保底：一户至少得有一条田。方位法在破碎地形上可能只找到一两个方向（岛上平地是零碎的），
      // 于是「农户」没有地可种、日程里「下自家田」没有落点。这里沿所有村道往镇外补扫一遍。
      if (made < households) {
         int add = this.farmFloor(households - made, field, net);
         this.diag.put("农田保底补", add);
         made += add;
         this.diag.put("农田", made);
      }
   }

   /**
    * 农田保底补扫：沿村道往镇外成条补田，直到够数。
    *
    * <p>为什么需要这一步：{@link #placeFarmlands} 是按方位成片铺的（真实田区就是成片的），
    * 但它要求那一整条方位上都是可耕地。岛上的平地是零碎的，有时候只有一个方位能用，
    * 出来的田就不够一户一条。保底补扫不追求成片，只保证「每户有地」这条下限。</p>
    *
    * @param need  还缺几条
    * @param field 田块资产
    * @param net   路网
    * @return 实际补出的条数
    */
   private int farmFloor(int need, AssetSpec field, List<RoadSpec> net) {
      int made = 0;
      String[] crops = new String[]{"grain", "vegetable", "rapeseed", "beans", "mulberry"};
      String[] palette = new String[]{"#9e9463", "#8b9159", "#b09b62", "#78843f", "#8f9a5c"};

      for(RoadSpec road : net) {
         if (made >= need) {
            break;
         }
         if (road.grade() < 1 || road.pathMeters() == null || road.pathMeters().size() < 2) {
            continue;
         }

         List<double[]> path = road.pathMeters();
         double[] cum = arcLengths(path);
         double total = cum[cum.length - 1];

         for(double s = 6.0; s < total - 6.0 && made < need; s += 18.0) {
            double[] p = pointAt(path, cum, s);
            double[] tan = chordDir(path, cum, s, 6.0);
            if (tan == null) {
               continue;
            }

            if (Math.hypot(p[0] - this.settleX, p[1] - this.settleZ) < this.townRadius * 0.9) {
               continue;   // 镇里的路两边是房子，不是田
            }

            for(int side = -1; side <= 1 && made < need; side += 2) {
               double nx = -tan[1] * (double)side;
               double nz = tan[0] * (double)side;

               for(double out = 10.0; out <= 34.0 && made < need; out += 8.0) {
                  double px = p[0] + nx * out;
                  double pz = p[1] + nz * out;
                  // 保底补扫也用正方田（与成片算法同一种田，不能一半方一半条）
                  double plot = 12.0;
                  double rot = Math.toDegrees(Math.atan2(tan[1], tan[0]));
                  double[][] poly = this.rect(px, pz, plot, plot, rot);
                  if (this.blockedFarmPlot(poly) != null) {
                     continue;
                  }

                  this.rasterPoly(poly, 1.0F);
                  this.markFieldPoly(poly);
                  int ci = this.rng.nextInt(crops.length);
                  String ownerRef = "";
                  if (!this.dwellingSites.isEmpty()) {
                     List<String> refs = new ArrayList(this.dwellingSites.keySet());
                     ownerRef = (String)refs.get(made % refs.size());
                  }

                  String code = this.nextCode("f_plot");
                  this.features.add(new PlacedFeature(code, "田地", "生产", "field", field == null ? null : field.code(), px, pz, plot, plot, rot, 0.16, 1, this.elevationAt(px, pz), (String)null, palette[ci], (String)null, px, pz, rot, poly, "{\"plot\":true,\"crop\":\"" + crops[ci] + "\"" + (ownerRef.isEmpty() ? "" : ",\"owner\":\"" + ownerRef + "\"") + "}", (String)null, 0, 1));
                  this.parcels.add(new PlacedParcel(this.nextCode("parcel_field"), "farm", px, pz, polyArea(poly), (double)this.t.buildable[Math.max(0, this.cellIndex(px, pz))], poly, (String)null, code));
                  ++made;
               }
            }
         }
      }

      return made;
   }

   private double[][] farmDirections(int want, double startR) {
      List<double[]> scored = new ArrayList();

      for(int k = 0; k < 24; ++k) {
         double ang = (double)k * Math.PI / (double)12.0F;
         int ok = 0;
         int n = 0;

         for(double r = startR + (double)8.0F; r <= startR + (double)220.0F; r += (double)12.0F) {
            double x = this.settleX + Math.cos(ang) * r;
            double z = this.settleZ + Math.sin(ang) * r;
            if (!(x < (double)24.0F) && !(z < (double)24.0F) && !(x > (double)this.size * this.mpc - (double)24.0F) && !(z > (double)this.size * this.mpc - (double)24.0F)) {
               int i = this.cellIndex(x, z);
               ++n;
               if (i >= 0 && this.plowable(i)) {
                  ++ok;
               }
            } else {
               ++n;
            }
         }

         scored.add(new double[]{ang, (double)ok / (double)Math.max(1, n) + this.fieldBearingBonus(ang)});
      }

      scored.sort((a, b) -> Double.compare(b[1], a[1]));
      List<double[]> out = new ArrayList();

      for(double[] c : scored) {
         // 不设绝对阈值：岛上的开阔地是零碎斑块，一整条 220 米射线上很难有 40% 可耕，
         // 旧口径「score < 0.40 就 break」在碎片地形上一个方位都选不出来（田全靠保底补扫）。
         // 改成取前 want 个互成 45° 的方位，让 farmBlock 自己用 bandFits 优雅失败——
         // 失败只浪费一次整段判定，比"一个方位都不给"好得多。
         if (out.size() >= want || c[1] < 0.02) {
            break;
         }

         boolean apart = true;

         for(double[] o : out) {
            if (Math.abs(angDiff(c[0], o[0])) < Math.toRadians((double)45.0F)) {
               apart = false;
               break;
            }
         }

         if (apart) {
            out.add(c);
         }
      }

      return (double[][])out.toArray(new double[0][]);
   }

   private double fieldBearingBonus(double ang) {
      if (this.p.regions != null && !this.p.regions.isEmpty()) {
         double best = (double)0.0F;

         for(TerrainRegion r : this.p.regions) {
            if ("FIELD".equals(r.kind())) {
               double want = TerrainRegion.bearingToRad(r.bearingDeg());
               double diff = Math.abs(ang - want);
               diff = Math.min(diff, (Math.PI * 2D) - diff);
               best = Math.max(best, 0.22 * Math.max((double)0.0F, (double)1.0F - diff / (Math.PI / 4D)));
            }
         }

         return best;
      } else {
         return (double)0.0F;
      }
   }

   /**
    * 铺一片农田：<b>一片正方形田块铺成的矩阵 + 近端一条田头路</b>。
    *
    * <p><b>为什么不是一张大网格</b>：旧实现把农区当成一块最大 250×200 米的矩形网格，在里面开
    * 「每 3 行一条横路 + 中间一条纵路」，再把田填进格子里。三个后果都是硬伤：
    * ① 30 条田全挤在镇子同一侧的同一条带里（用户一眼看出「全都堆到一起」）；
    * ② 网格铺到坡上/林子里就被整行拒掉，剩下的田在带子里东缺一块西缺一块；
    * ③ 那条「纵贯中线」的土路笔直穿过整片田，在航拍上是一条不可能存在的工程线。</p>
    *
    * <p><b>真实的样子（用户口径）</b>：田是从村口那条路「长」出去的一片<b>正方地块矩阵</b>——
    * 12 米见方的地块一块挨一块排成 cols×rows 的棋盘，块间只留 1 米田埂，谁也不压着谁；
    * 矩阵近端横着一条田头路（人和牛车在这条线上掉头、装车），田头路再用一条几米长的下地土路
    * 接到村道上。整片矩阵按 pitch 的整数倍取位，所以相邻两片的田埂永远对得上，不会错成拼布。</p>
    *
    * <p>放不下时按 {@link #farmMatrices(int)} 的候选退档（6×3 → 5×3 → … → 2×2），
    * 而不是把田拆回长条：宁可这片少几块，也要保持「一块挨一块」的读法。</p>
    *
    * @param ang    农区方位（弧度）
    * @param quota  这片最多铺几块田
    * @param field  田块资产
    * @param net    路网（会把田头路/下地土路加进去）
    * @param cost   寻路代价场（保留参数：下地土路若改走寻路要用）
    * @param built  建成物占用场（田埂避让用）
    * @param startR 农区起点半径（米）
    * @return 实际铺出的田块数
    */
   private int farmBlock(double ang, int quota, AssetSpec field, List<RoadSpec> net, float[] cost, float[] built, double startR) {
      // ① 落脚点候选：这一侧的若干条能走车的路（外侧优先）。田必须「挨着路」——
      // 没有路，人怎么下地、粮怎么运回来？但一条路排不下的时候要能换下一条：
      // 只试一个落脚点会让「一片田都种不下」——实测这个岛外圈的路全被林子/坡卡住，
      // 只试最外侧那条就 6 次报「农区:地尽」，30 块田只种出 12 块。
      List<double[]> anchors = this.roadAnchorsToward(net, ang, startR);
      if (anchors.isEmpty()) {
         this.bump("农区:无路可依");
         return 0;
      }
      for(double[] cand : anchors) {
         int made = this.farmBlockAt(ang, quota, field, net, cost, built, cand);
         if (made > 0) {
            return made;
         }
      }
      this.bump("农区:地尽");
      return 0;
   }

   /**
    * 在一块指定的落脚点上铺一片田（{@link #farmBlock} 的内层：一个落脚点试一次）。
    *
    * @param ang    农区方位（弧度，仅作初值；真正的田垄方向由落脚点所在的路决定）
    * @param quota  这片最多铺几块田
    * @param field  田块资产
    * @param net    路网（会把田头路/下地土路加进去）
    * @param cost   寻路代价场（保留参数：下地土路若改走寻路要用）
    * @param built  建成物占用场（田埂避让用）
    * @param anchor 落脚点（村道上的点，米）
    * @return 实际铺出的田块数（0=这个落脚点排不下）
    */
   private int farmBlockAt(double ang, int quota, AssetSpec field, List<RoadSpec> net, float[] cost, float[] built, double[] anchor) {
      double cos = Math.cos(ang);
      double sin = Math.sin(ang);

      // ①b 田垄方向跟着「这片田挨着的那条路」走：取路切线转 90°，朝外侧就是离村法线。
      // 旧口径直接用「从镇心射出去的方位」当田垄方向，路一旦斜着走，田块就和路斜交，
      // 下地土路只能斜切过田角——航拍上就是一片被斜线切碎的田（用户：道路生成得这么碎）。
      double[] tan = this.nearestRoadDir(net, anchor[0], anchor[1]);
      if (tan != null) {
         double nx = -tan[1];
         double nz = tan[0];
         if (nx * (anchor[0] - this.settleX) + nz * (anchor[1] - this.settleZ) < (double)0.0F) {
            nx = tan[1];
            nz = -tan[0];
         }
         cos = nx;
         sin = nz;
         ang = Math.atan2(sin, cos);
      }

      int made = 0;

      // 一块农区 = 一片「正方田」铺成的矩阵（用户口径：一个一个挨着、像矩阵）。
      // 旧口径是 5.3×36 米的长条（条田制），俯视图像一排排木板；改成正方块之后是一块整齐的棋盘，
      // 块与块之间只有 1 米田埂，整片农区的近端横一条田头路——这才是"一户一块地"的真实读法。
      //
      // 户均地块尺度：12 米见方（144 平米 ≈ 两分地），间距 13 米（1 米田埂）。
      double pitch = 13.0;
      double plot = 12.0;
      // 落脚点（村道上的点）到第一排田心的距离：田头路 2.6 米 + 半个田块 + 埂宽，取 8 米起
      double front = 8.0;

      // 矩阵规模候选：先按 quota 排成「刚好装满的整齐矩形」（优先宽而浅：真实田区沿路展开，
      // 不会纵深很长），放不下就退一档。碎片化地形上 6×3（78×39 米）常常塞不进，
      // 但 3×2 还是有地方的——退档比整片放弃更能保住"一块挨一块"的形态。
      int[][] mats = farmMatrices(quota);
      int cols = mats[0][0];
      int rows = mats[0][1];
      double bandD = rows * pitch;
      double halfW = cols * pitch / (double)2.0F;

      // ② 整块取位：整块矩阵 + 田头路一次判可用。任何一角是坡/林/房/路，整块换位置——
      // 这样田既不会压到路上，也不会互相重叠（田块按 pitch 精确排布，几何上就不叠）。
      // 挪动只按 pitch 的整数倍：挪完还是同一套格网，相邻两片的田埂和田头路永远对得上；
      // 旧口径按 4/8 米挪，结果每排错开半格，t3 里那片田就成了"打乱的拼布"。
      double bestLx = 0;
      double bestS = 0;
      boolean found = false;
      for (int mi = 0; mi < mats.length && !found; ++mi) {
         int mc = mats[mi][0];
         int mr = mats[mi][1];
         double md = mr * pitch;
         double mh = mc * pitch / (double)2.0F;
         for (double s : new double[]{front, front + pitch, front + 2.0 * pitch}) {
            for (double lx : new double[]{0, -pitch, pitch, -2.0 * pitch, 2.0 * pitch}) {
               if (this.bandFits(anchor, cos, sin, s, md, mh, lx)
                       && this.spurOk(anchor, cos, sin, s, built)) {
                  cols = mc;
                  rows = mr;
                  bandD = md;
                  halfW = mh;
                  bestLx = lx;
                  bestS = s;
                  found = true;
                  break;
               }
            }
            if (found) {
               break;
            }
         }
      }
      if (!found) {
         this.bump("农区:地尽");
         return 0;
      }

         // 田头线位置（田头路横铺在这条线上，下地土路从落脚点沿法线接到这里）
         double trackS = bestS - 2.6;
         double jx = anchor[0] + cos * trackS;
         double jz = anchor[1] + sin * trackS;

         // ③ 铺矩阵：行沿离村方向、列沿法线，间距 13 米（12 米田 + 1 米田埂）。
         // 每格再判一次（整块判的是外接矩形，格里可能压到路/坎/树）；判不过只跳过这一格，
         // 不改行列位置——宁可留一个缺口，也不让整排歪掉（歪一排整片就成了拼布）。
         int before = made;
         String[] crops = new String[]{"grain", "vegetable", "rapeseed", "beans", "fallow", "mulberry"};
         String[] palette = new String[]{"#9e9463", "#8b9159", "#b09b62", "#78843f", "#9a8d67", "#8f9a5c"};

         for(int r = 0; r < rows && made < quota; ++r) {
            // 最后一行不满时居中排：矩阵少一格也左右对称，不会缺在一头
            int rowCount = Math.min(cols, quota - made);
            for(int c = 0; c < rowCount && made < quota; ++c) {
               this.bump("农垄");
               double along = bestS + pitch * ((double)r + 0.5);
               double lat = ((double)c - (double)(rowCount - 1) / (double)2.0F) * pitch + bestLx;
               double px = anchor[0] + cos * along - sin * lat;
               double pz = anchor[1] + sin * along + cos * lat;
               // 整块已经判过，这里只抖 ±0.25°，让田埂看起来是"人手划的"而不是 CAD 画的；
               // 抖太大会让相邻田块的角互相插进去，反而像没对齐。
               double rot = Math.toDegrees(ang) + this.rng.nextDouble(-0.25, 0.25);
               // 正方形田块：短边 = 长边 = 12 米，不再有"长条"
               double[][] poly = this.rect(px, pz, plot, plot, rot);
               String why = this.blockedFarmPlot(poly);
               if (why != null) {
                  this.bump("田" + why);
                  continue;
               }
               this.rasterPoly(poly, 1.0F);
               this.markFieldPoly(poly);
               int ci = this.rng.nextInt(crops.length);
               // 田按户轮转归属：真实的条田制就是一户几条、散在各处（轮作与地力公平）。
               String ownerRef = "";
               if (!this.dwellingSites.isEmpty()) {
                  List<String> refs = new ArrayList(this.dwellingSites.keySet());
                  ownerRef = (String)refs.get(made % refs.size());
               }

               String code = this.nextCode("f_plot");
               this.features.add(new PlacedFeature(code, "田地", "生产", "field", field == null ? null : field.code(), px, pz, plot, plot, rot, 0.16, 1, this.elevationAt(px, pz), (String)null, palette[ci], (String)null, px, pz, rot, poly, "{\"plot\":true,\"crop\":\"" + crops[ci] + "\"" + (ownerRef.isEmpty() ? "" : ",\"owner\":\"" + ownerRef + "\"") + "}", (String)null, 0, 1));
               this.parcels.add(new PlacedParcel(this.nextCode("parcel_field"), "farm", px, pz, polyArea(poly), (double)this.t.buildable[Math.max(0, this.cellIndex(px, pz))], poly, (String)null, code));
               ++made;
            }
         }

         // ④ 先种田、后修路：一块田都没种下时不修田头路。田头是整块农区近端横着的一条土路，
         // 人和牛车在这条线上掉头、装车；方向垂直于"离村方向"（沿法线），不该顺着田垄走。
         if (made > before) {
            // 田头线沿法线横铺，横向范围必须把村道落脚点（lx=0）包进去——
            // 否则田头路与村道之间只能靠一条斜的"下地土路"接上，那条斜线正好横切田角。
            double lFrom = Math.min(bestLx - halfW, (double)0.0F);
            double lTo = Math.max(bestLx + halfW, (double)0.0F);
            this.tracksTo(net, jx, jz, cos, sin, lFrom, lTo, built);
            this.diag.merge("田段落位", 1, Integer::sum);

            // ⑤ 田头路接村道：沿离村法线直着进去 5~31 米。牛车下地走的是垂直路面的短坡道，
            // 斜插田块的"近路"在真实农田里不存在；旧口径从落脚点直接连田头线中点，
            // 一旦田块整体横向偏移（bestLx≠0）就连成了斜线。
            if (Math.hypot(jx - anchor[0], jz - anchor[1]) > 2.5) {
               List<double[]> link = this.linkClearOfBuildings(anchor[0], anchor[1], jx, jz, null);
               if (link == null) {
                  // 绕不过去就不修这段：田头没有下地土路还能接受，把屋压了不能接受。
                  this.bump("下地土路:绕不通");
               } else {
                  int seq = this.fieldTrackSeq + 1;
                  this.fieldTrackSeq = seq;
                  RoadSpec spec = new RoadSpec("farm_link_" + seq, "下地土路", "track", 0, 2.2, "dirt", link, 0);
                  net.add(spec);
                  this.blockRoads(List.of(spec));
                  this.diag.merge("下地土路", 1, Integer::sum);
               }
            }

            double zx = anchor[0] + cos * (bestS + bandD / (double)2.0F) - sin * bestLx;
            double zz = anchor[1] + sin * (bestS + bandD / (double)2.0F) + cos * bestLx;
            this.groups.add(new PlacedGroup(this.nextCode("grp_field"), "田片", "field", zx, zz, Math.max(halfW, 18.0), Math.toDegrees(ang), "farm", made + " 块地"));
         }

      return made;
   }

   /**
    * 一片田最多能排多大的矩阵（候选按「面积降序、同面积取更宽」）。
    *
    * <p>田块只用正方形 + 整米田埂，矩阵必然是 cols×rows 的整齐矩形；碎片地形上大矩阵排不下时
    * 退成小矩阵，而不是把田拆回长条（用户明确要「一块挨一块的矩阵」，不要条田）。</p>
    *
    * @param quota 这一侧最多铺几块
    * @return 候选矩阵 {{cols, rows}, ...}，第一个是首选规模
    */
   private static int[][] farmMatrices(int quota) {
      int[][] all = new int[][]{{6, 3}, {6, 2}, {5, 3}, {5, 2}, {4, 3}, {4, 2},
            {3, 3}, {3, 2}, {2, 2}, {3, 1}, {2, 1}, {1, 1}};
      List<int[]> fits = new ArrayList();
      int q = Math.max(1, quota);

      for(int[] m : all) {
         if (m[0] * m[1] <= q) {
            fits.add(m);
         }
      }

      if (fits.isEmpty()) {
         fits.add(new int[]{1, 1});
      }

      fits.sort((a, b) -> {
         int area = Integer.compare(b[0] * b[1], a[0] * a[1]);
         return area != 0 ? area : Integer.compare(b[0], a[0]);
      });
      return (int[][])fits.toArray(new int[0][]);
   }

   /**
    * 一整段田（across 条并排垄 + 田头）在这个位置放不放得下。
    *
    * <p>整段判一次，是为了让「一段田」在图上是一整块，而不是一堆勉强拼起来的碎条。
    * 判的内容与单条垄相同（{@link #blockedFarmPlot}：可耕地占比、与已有田的重叠、占用/坡度），
    * 只是把整个矩形一起判——任何一角是坡/林/房，整段就换位置。</p>
    *
    * @param anchor 田区落脚点（村道上的点）
    * @param cos    农区方位余弦
    * @param sin    农区方位正弦
    * @param s      该段近端离落脚点的距离（米）
    * @param len    垄长（米）
    * @param halfW  该段半宽（米）
    * @param lx     横向偏移（米）
    * @return true=整段可用
    */
   private boolean bandFits(double[] anchor, double cos, double sin, double s, double len, double halfW, double lx) {
      // 田头路一起判：连田头路都铺不出来的位置，田种下去就是"没有路连着的田"（用户点名）。
      // 所以矩形从 s-4.5（田头路走廊）一直盖到 s+len（远端）。
      double s0 = s - 4.5;
      double depth = len + 4.5;
      double cx = anchor[0] + cos * (s0 + depth / (double)2.0F) - sin * lx;
      double cz = anchor[1] + sin * (s0 + depth / (double)2.0F) + cos * lx;
      double rot = Math.toDegrees(Math.atan2(sin, cos));
      // 长轴沿离村方向（len），短边是整块宽度（2*halfW）；外扩 1 米给田埂留余量
      double[][] band = this.rect(cx, cz, depth + 1.0, halfW * (double)2.0F + 1.0, rot);
      String why = this.blockedFarmPlot(band);
      if (why != null) {
         this.bump("田段" + why);
         return false;
      }
      return true;
   }

   /**
    * 找这一方位上的一条能走车的路（作为这片田的「落脚点」）。
    *
    * <p><b>从最外圈往回找</b>：旧的写法取「离射线最近」的那个路面点，于是常常落在镇子边缘的
    * 那条街上——街两侧全是房子，从街上垂直往外的「下地土路」必然从人家屋里穿过去
    * （实测：农舍被农场土路压了 12 格）。改成从 {@code startR+70} 往回扫，取<b>最外侧</b>
    * 那条路：那里通常已经是出村路/田边路，田就铺在它的外侧，土路不会穿房。</p>
    *
    * @param net    路网
    * @param ang    方位（弧度）
    * @param startR 期望距离（米，从聚落中心）
    * @return 路面点 {x,z}；找不到返回 null
    */
   private List<double[]> roadAnchorsToward(List<RoadSpec> net, double ang, double startR) {
      double cos = Math.cos(ang);
      double sin = Math.sin(ang);
      double lo = Math.max(30.0, startR - 18.0);
      List<double[]> out = new ArrayList();

      for(double r = startR + 70.0; r >= lo; r -= 5.0) {
         double x = this.settleX + cos * r;
         double z = this.settleZ + sin * r;
         if (x < 40.0 || z < 40.0 || x > (double)this.size * this.mpc - 40.0 || z > (double)this.size * this.mpc - 40.0) {
            continue;
         }

         double[] pt = this.nearestRoadPoint(net, x, z, 1);
         if (pt == null || pt[2] > 16.0) {
            continue;
         }
         // 相邻候选至少隔开 14 米：同一条路上挨着的点排不下，下一个点也一样排不下。
         boolean dup = false;
         for(double[] o : out) {
            if (Math.hypot(o[0] - pt[0], o[1] - pt[1]) < 14.0) {
               dup = true;
               break;
            }
         }
         if (!dup) {
            out.add(new double[]{pt[0], pt[1]});
            if (out.size() >= 4) {
               break;
            }
         }
      }

      return out;
   }

   /**
    * 在一条「田头线」上铺土路（复用 {@link #latticeLine} 的净空检查）。
    *
    * <p>田头线是<b>横着</b>的：这条线垂直于条田的长边（沿法线 (-sin, cos) 方向走），
    * 跨度就是这一排条田的总宽度。所以这里给 {@code latticeLine} 传 {@code horizontal=false}。</p>
    *
    * @param net   路网
    * @param cx    线中点 X（米）
    * @param cz    线中点 Z（米）
    * @param cos   方位余弦（条田长边方向）
    * @param sin   方位正弦
    * @param from  起点偏移（米，横向）
    * @param to    终点偏移（米）
    * @param built 建成物占用场
    */
   private void tracksTo(List<RoadSpec> net, double cx, double cz, double cos, double sin, double from, double to, float[] built) {
      List<double[]> pts = this.latticeLine(cx, cz, cos, sin, from, to, (double)0.0F, false, built);
      int added = this.addFieldTrack(pts, net, 1);
      if (added > 0) {
         this.diag.merge("田头路", added, Integer::sum);
      }
   }

   private List<double[]> latticeLine(double zx, double zz, double cos, double sin, double from, double to, double offset, boolean horizontal, float[] built) {
      List<double[]> out = new ArrayList();
      List<double[]> run = new ArrayList();
      double step = Math.max((double)1.0F, this.mpc);
      double gap = (double)0.0F;

      for(double t = from; t <= to + 0.01; t += step) {
         double x;
         double z;
         if (horizontal) {
            x = zx + t * cos - offset * sin;
            z = zz + t * sin + offset * cos;
         } else {
            x = zx + offset * cos - t * sin;
            z = zz + offset * sin + t * cos;
         }

         int i = this.cellIndex(x, z);
         boolean ok = i >= 0 && this.trackable(i, built);
         this.bump("埂采样");
         if (!ok) {
            String var10001 = this.trackBlock(i, built);
            this.bump("埂阻:" + var10001);
         }

         if (ok) {
            if (gap > (double)12.0F) {
               this.flushRun(run, out);
            }

            gap = (double)0.0F;
            run.add(new double[]{x, z});
         } else {
            gap += step;
            if (this.nearBuilt(i, built, 2) || gap > (double)12.0F) {
               this.flushRun(run, out);
               gap = (double)13.0F;
            }
         }
      }

      this.flushRun(run, out);
      return out;
   }

   private String trackBlock(int i, float[] built) {
      if (i < 0) {
         return "越界";
      } else if (this.t.waterCell(i)) {
         // 田埂/田头路也不能下水：waterCell 是水陆唯一入口（含高于海平面的内陆湖）
         return "水面";
      } else if (this.t.buildable[i] < 0.05F) {
         return "不可建";
      } else if ((double)this.t.slope[i] > 0.32) {
         return "坡";
      } else {
         return this.nearBuilt(i, built, 2) ? "房" : "其他";
      }
   }

   /**
    * 给定「田头线离村距离 s」时，这条田片的下地土路（沿法线从落脚点进去 s-2.6 米）走不走得通。
    *
    * @param anchor 村道上的落脚点
    * @param cos    离村法线 X
    * @param sin    离村法线 Z
    * @param s      田头线离落脚点的距离（米）
    * @param built  建成物占用场
    * @return true=这段土路可以修
    */
   private boolean spurOk(double[] anchor, double cos, double sin, double s, float[] built) {
      double trackS = s - 2.6;
      if (trackS <= 2.5) {
         return true;
      }
      return this.spurClear(anchor[0], anchor[1], anchor[0] + cos * trackS,
              anchor[1] + sin * trackS, built);
   }

   /**
    * 一段「下地土路」走不走得通：沿线每米采一格，必须是可修田埂的地（不压房、不下水、不太陡）。
    *
    * <p>为什么要在种田之前判：田块一旦落地就按行按列排好了，事后再发现土路要从房里穿过去，
    * 只能二选一——要么压房子，要么留一片没路的田。提前判就能整片换位置。</p>
    *
    * @param ax    起点 X（村道上的落脚点，米）
    * @param az    起点 Z
    * @param bx    终点 X（田头线中点）
    * @param bz    终点 Z
    * @param built 建成物占用场
    * @return true=这一段可以修土路
    */
   private boolean spurClear(double ax, double az, double bx, double bz, float[] built) {
      double len = Math.hypot(bx - ax, bz - az);
      int steps = Math.max(2, (int)Math.ceil(len / Math.max((double)1.0F, this.mpc)));
      for(int s = 1; s <= steps; ++s) {
         double tt = (double)s / (double)steps;
         int i = this.cellIndex(ax + (bx - ax) * tt, az + (bz - az) * tt);
         if (i < 0 || this.t.waterCell(i) || this.t.buildable[i] < 0.05F
                 || (double)this.t.slope[i] > 0.32 || this.nearBuilt(i, built, 1)) {
            return false;
         }
      }
      return true;
   }

   private boolean trackable(int i, float[] built) {
      int x = i % this.size;
      int z = i / this.size;
      if (x >= 2 && z >= 2 && x < this.size - 2 && z < this.size - 2) {
         if (!this.t.waterCell(i) && !(this.t.buildable[i] < 0.05F) && !((double)this.t.slope[i] > 0.32)) {
            return !this.nearBuilt(i, built, 2);
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private boolean nearBuilt(int i, float[] built, int rad) {
      if (built == null) {
         return false;
      } else {
         int x = i % this.size;
         int z = i / this.size;

         for(int dz = -rad; dz <= rad; ++dz) {
            for(int dx = -rad; dx <= rad; ++dx) {
               int xx = x + dx;
               int zz = z + dz;
               if (xx >= 0 && zz >= 0 && xx < this.size && zz < this.size && built[zz * this.size + xx] > 0.05F) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   private void flushRun(List<double[]> run, List<double[]> out) {
      if (run.size() >= 12) {
         out.addAll(run);
         out.add((double[]) null);
      }

      run.clear();
   }

   private int addFieldTrack(List<double[]> runs, List<RoadSpec> net, int seq) {
      if (runs.isEmpty()) {
         return 0;
      } else {
         int added = 0;
         int seqNo = 0;
         List<double[]> cur = new ArrayList();

         for(double[] pt : runs) {
            if (pt != null) {
               cur.add(pt);
            } else {
               ++seqNo;
               if (cur.size() < 12) {
                  this.bump("埂弃短");
               }

               if (cur.size() >= 12) {
                  List<double[]> thin = RoadRouter.simplify(cur, (double)1.5F);
                  if (thin.size() >= 2) {
                     int var10004 = this.fieldTrackSeq + 1;
                     RoadSpec track = new RoadSpec("farm_" + (this.fieldTrackSeq = var10004), "田埂土路" + this.fieldTrackSeq, "track", 0, 1.8, "dirt", thin, 0);
                     net.add(track);
                     this.blockRoads(List.of(track));
                     ++added;
                  }
               }

               cur = new ArrayList();
            }
         }

         return added;
      }
   }

   private double[] nearestTrackPoint(List<RoadSpec> net, double x, double z) {
      double best = Double.MAX_VALUE;
      double[] out = null;

      for(RoadSpec r : net) {
         if (r.grade() <= 0 && r.pathMeters() != null) {
            for(double[] p : r.pathMeters()) {
               double dd = Math.hypot(p[0] - x, p[1] - z);
               if (dd < best) {
                  best = dd;
                  out = p;
               }
            }
         }
      }

      return out;
   }

   private void capRoadEnds(List<RoadSpec> net, List<AssetSpec> assets, double coreX, double coreZ) {
      AssetSpec home = this.pickByCategory(assets, "民居");
      if (home != null && !this.budgetFull()) {
         int hamlets = 0;
         int houses = 0;

         for(RoadSpec r : net) {
            if (hamlets >= 4) {
               break;
            }

            if (r.pathMeters() != null && r.pathMeters().size() >= 2 && !"pier".equals(r.kind()) && !"connector".equals(r.kind())) {
               List<double[]> path = r.pathMeters();

               for(int e = 0; e < 2 && hamlets < 4; ++e) {
                  double[] tip = e == 0 ? (double[])path.get(0) : (double[])path.get(path.size() - 1);
                  if (!(Math.hypot(tip[0] - coreX, tip[1] - coreZ) < this.townRadius * 1.02) && !(this.distToOtherRoads(net, r, tip[0], tip[1]) < (double)20.0F)) {
                     int n = this.hamletAt(r, e, home, tip);
                     if (n > 0) {
                        ++hamlets;
                        houses += n;
                     }
                  }
               }
            }
         }

         if (hamlets > 0) {
            this.diag.merge("路尽头小屯", hamlets, Integer::sum);
            this.diag.merge("小屯人家", houses, Integer::sum);
         }

      }
   }

   private int hamletAt(RoadSpec road, int endIdx, AssetSpec home, double[] tip) {
      List<double[]> path = road.pathMeters();
      double[] prev = endIdx == 0 ? (double[])path.get(1) : (double[])path.get(path.size() - 2);
      double ux = tip[0] - prev[0];
      double uz = tip[1] - prev[1];
      double len = Math.hypot(ux, uz);
      if (len < 1.0E-6) {
         return 0;
      } else {
         ux /= len;
         uz /= len;
         double baseAng = Math.atan2(uz, ux);
         double rotd = Math.toDegrees(baseAng);
         double snx = -uz;
         double snz = ux;
         // ① 禾场：路尽头外 8 米的一块夯土场院（打谷 / 堆柴 / 拴牲口），是小屯的中心空地
         double yx = tip[0] + ux * (double)8.0F;
         double yz = tip[1] + uz * (double)8.0F;
         double yw = (double)13.0F + this.rng.nextDouble() * (double)5.0F;
         double yd = (double)11.0F + this.rng.nextDouble() * (double)4.0F;
         if (this.canPlace(yx, yz, yw, yd, rotd)) {
            this.commit(yx, yz, yw, yd, rotd);
            double[][] yPoly = this.rect(yx, yz, yw, yd, rotd);
            String yardCode = this.nextCode("f_yard");
            this.features.add(new PlacedFeature(yardCode, "禾场", "生产", "plaza", (String)null, yx, yz, yw, yd, rotd, 0.05, 1, this.elevationAt(yx, yz), (String)null, "#c2b28c", (String)null, yx, yz, rotd, yPoly, "{\"role\":\"threshing\"}", (String)null, 0, 2));
            this.parcels.add(new PlacedParcel(this.nextCode("parcel_yard"), "yard", yx, yz, yw * yd, (double)1.0F, yPoly, road.code(), (String)null));
         }
         // ② 人家：<b>沿路的末段两侧并排</b>（§V1.5-§7.2）。
         // 旧口径把三四户人家摆在禾场周围一圈（距路头 10~15 米），门口朝禾场——
         // 于是这几户在图上离路十几米、没有任何进道，像凭空落在草地上的孤屋。
         // 真实的路尽头小屯就是"沿路再排几户"：门朝路、退线一米半，路本身就是它的街。
         int made = 0;
         for(int side = -1; side <= 1; side += 2) {
            for(int k = 0; k < 3; ++k) {
               double s = (double)3.5F + (double)k * (double)7.0F + this.rng.nextDouble() * (double)1.6F;
               double hw = 6.2 + this.rng.nextDouble() * (double)2.2F;
               double hd = 5.0 + this.rng.nextDouble() * (double)1.6F;
               double offset = road.width() / (double)2.0F + (double)1.6F + hd / (double)2.0F;
               double hx = tip[0] - ux * s + snx * (double)side * offset;
               double hz = tip[1] - uz * s + snz * (double)side * offset;
               if (!this.canPlace(hx, hz, hw, hd, rotd)) {
                  continue;
               }
               this.commit(hx, hz, hw, hd, rotd);
               double doorX = hx - snx * (double)side * (hd / (double)2.0F);
               double doorZ = hz - snz * (double)side * (hd / (double)2.0F);
               double doorDir = Math.toDegrees(Math.atan2(-snx * (double)side, -snz * (double)side));
               String code = this.nextCode("f_hamlet_house");
               this.features.add(new PlacedFeature(code, nameOr(home, hx, hz), "民居", "building", home.code(), hx, hz, hw, hd, rotd, 3.2 + this.rng.nextDouble() * (double)1.6F, 1, this.elevationAt(hx, hz), home.wallColor(), home.roofColor(), home.roofShape(), doorX, doorZ, doorDir, this.rect(hx, hz, hw, hd, rotd), "{\"hamlet\":true}", (String)null, 1, 1));
               this.parcels.add(new PlacedParcel(this.nextCode("parcel_hamlet"), "homestead", hx, hz, hw * hd + (double)5.0F, (double)this.t.buildable[Math.max(0, this.cellIndex(hx, hz))], this.rect(hx, hz, hw, hd, rotd), road.code(), code));
               ++made;
            }
         }
         if (made == 0) {
            return 0;
         } else {
            this.groups.add(new PlacedGroup(this.nextCode("grp_hamlet"), "路尽头人家", "hamlet", tip[0], tip[1], (double)20.0F, rotd, home.code(), "沿路几家 + 禾场"));
            return made;
         }
      }
   }

   private double distToOtherRoads(List<RoadSpec> roads, RoadSpec self, double x, double z) {
      double best = Double.MAX_VALUE;

      for(RoadSpec r : roads) {
         if (r != self && r.pathMeters() != null && r.pathMeters().size() >= 2) {
            double[] q = MapNetwork.nearestOnPath(r.pathMeters(), x, z);
            best = Math.min(best, q[2]);
         }
      }

      return best;
   }

   private void placeForests(List<AssetSpec> assets, Map<String, MapSites.Site> sites) {
      MapSites.Site core = (MapSites.Site)sites.get("core");
      double coreX = core == null ? (double)0.0F : core.x();
      double coreZ = core == null ? (double)0.0F : core.z();
      int made = 0;

      for(int attempt = 0; attempt < 260 && made < 3; ++attempt) {
         int cx = this.rng.nextInt(this.size);
         int cz = this.rng.nextInt(this.size);
         int i = cz * this.size + cx;
         if (!((double)this.t.forest[i] < 0.62) && !((double)this.t.buildable[i] > 0.55) && !(this.occ[i] > 0.0F)) {
            double wx = (double)cx * this.mpc;
            double wz = (double)cz * this.mpc;
            if (!(Math.hypot(wx - coreX, wz - coreZ) < (double)90.0F)) {
               double r = (double)26.0F + this.rng.nextDouble() * (double)34.0F;
               if (this.canPlace(wx, wz, r * 1.6, r * 1.4, (double)0.0F)) {
                  this.commit(wx, wz, r * 1.6, r * 1.4, (double)0.0F);
                  String code = this.nextCode("f_forest");
                  AssetSpec fs = this.findKind(assets, "forest");
                  this.features.add(new PlacedFeature(code, "林地", "自然", "forest", fs == null ? null : fs.code(), wx, wz, r * 1.6, r * 1.4, (double)0.0F, (double)8.0F + this.rng.nextDouble() * (double)4.0F, 1, this.elevationAt(wx, wz), (String)null, "#3f5c34", (String)null, wx, wz, (double)0.0F, this.rect(wx, wz, r * 1.6, r * 1.4, (double)0.0F), "{\"density\":0.8}", (String)null, 0, 1));
                  this.parcels.add(new PlacedParcel(this.nextCode("parcel_forest"), "forest", wx, wz, r * 1.6 * r * 1.4, (double)this.t.buildable[i], this.rect(wx, wz, r * 1.6, r * 1.4, (double)0.0F), (String)null, code));
                  this.groups.add(new PlacedGroup(this.nextCode("grp_forest"), "林地", "forest", wx, wz, r, (double)0.0F, "natural", "成片林地"));
                  ++made;
               }
            }
         }
      }

   }

   /**
    * 码头/渡口：从水线起步伸进深水的一条木栈桥。
    *
    * <p>两个硬约束：① 栈桥必须从<b>水线</b>起步（否则桥墩会浮在离岸几十米的水面上，这比不好看更严重——
    * 它不符合物理）；② 名字必须来自世界数据（契约点名的「月牙湾渡口」），不能退化成通用「码头」，
    * 否则日程落点、对话里的「去渡口」都对不上东西。</p>
    *
    * @param assets 资产类型库
    * @param sites  语义落位点
    * @param roads  路网
    */
   private void placeHarbor(List<AssetSpec> assets, Map<String, MapSites.Site> sites, List<RoadSpec> roads) {
      MapSites.Site gate = (MapSites.Site)sites.get("gate");
      if (gate != null) {
         AssetSpec pier = this.findKind(assets, "pier");
         int gx = (int)Math.round(gate.x() / this.mpc);
         int gz = (int)Math.round(gate.z() / this.mpc);
         double bestAng = (double)0.0F;
         int bestLen = 0;
         int bestStart = 0;
         double bestScore = -Double.MAX_VALUE;

         for(int k = 0; k < 24; ++k) {
            double ang = (double)k * Math.PI / (double)12.0F;
            int start = -1;
            int len = 0;

            for(int s = 1; s < 44; ++s) {
               int ax = (int)Math.round((double)gx + Math.cos(ang) * (double)s);
               int az = (int)Math.round((double)gz + Math.sin(ang) * (double)s);
               if (ax < 0 || az < 0 || ax >= this.size || az >= this.size) {
                  break;
               }

               // 栈桥必须伸进<b>海</b>：只按海面判（{@code seaCell}），内陆湖与河道不算——
               // 否则码头会长到湖中央。水陆的唯一入口仍在 TerrainData，这里不自己比高程。
               boolean water = this.t.seaCell(az * this.size + ax);
               if (start < 0) {
                  if (water) {
                     start = s;
                     len = s;
                  }
               } else {
                  if (!water) {
                     break;
                  }

                  len = s;
               }
            }

            if (start > 0 && len - start >= 4) {
               double score = (double)(len - start) - 2.2 * (double)start;
               if (score > bestScore) {
                  bestScore = score;
                  bestLen = len;
                  bestStart = start;
                  bestAng = ang;
               }
            }
         }

         if (bestLen - bestStart >= 6 && bestStart <= 14) {
            int pierStart = Math.max(1, bestStart - 4);
            double pierLen = (double)(bestLen - pierStart) * this.mpc;
            double midS = (double)(pierStart + bestLen) / (double)2.0F * this.mpc;
            double cx = gate.x() + Math.cos(bestAng) * midS;
            double cz = gate.z() + Math.sin(bestAng) * midS;
            double rot = Math.toDegrees(bestAng);
            double ux = Math.cos(bestAng);
            double uz = Math.sin(bestAng);
            String code = this.nextCode("f_pier");
            String pierName = this.harborName != null && !this.harborName.isBlank() ? this.harborName : "码头";
            String anchorEsc = this.harborAnchor == null ? "" : this.harborAnchor.replace("\"", "");
            String pierSemantic = this.harborName == null ? "{\"role\":\"harbor\"}" : "{\"facility\":true,\"role\":\"harbor\",\"key\":\"pier\",\"anchor\":\"" + anchorEsc + "\"}";
            // 桥面宽度按"能过一辆板车"给：3.2 米在俯视图上只是一根木条，
            // 4.4 米才看得出"这是能卸货的栈桥"。
            double deck = 4.4;
            // 栈桥的「门」在岸上根节点：门朝岸（rot+180），不是朝水里——
            // door_dir 的语义是「门所在墙面的外法向」，朝海那一侧没有门。
            double rootX = gate.x() + ux * (double)pierStart * this.mpc;
            double rootZ = gate.z() + uz * (double)pierStart * this.mpc;
            this.features.add(new PlacedFeature(code, pierName, "水工", "pier", pier == null ? null : pier.code(), cx, cz, pierLen, deck, rot, 0.6, 1, this.t.seaLevel, (String)null, "#8a6b4a", (String)null, rootX, rootZ, rot + 180.0, this.rect(cx, cz, pierLen, deck, rot), pierSemantic, "gate", 0, 4));
            // 桥头（T 字）：栈桥到深水处横出一段，船才有地方并靠——
            // 一根直木条既是"敷衍"，也不符合真实栈桥的形态。
            double headLen = Math.max(6.0, Math.min(11.0, pierLen * 0.42));
            double headS = (double)bestLen * this.mpc - headLen / 2 - 0.6;
            double hx = gate.x() + ux * headS;
            double hz = gate.z() + uz * headS;
            this.features.add(new PlacedFeature(this.nextCode("f_pier"), pierName + "·桥头", "水工", "pier", pier == null ? null : pier.code(), hx, hz, headLen, deck + 1.2, rot + 90.0, 0.6, 1, this.t.seaLevel, (String)null, "#8a6b4a", (String)null, rootX, rootZ, rot, this.rect(hx, hz, headLen, deck + 1.2, rot + 90.0), "{\"role\":\"harbor\",\"part\":\"head\",\"head\":true}", "gate", 0, 4));
            this.groups.add(new PlacedGroup(this.nextCode("grp_pier"), pierName, "compound", cx, cz, pierLen / (double)2.0F, rot, "harbor", "渔船靠岸、货物上岸"));
            if (this.harborName != null) {
               this.diag.merge("契约设施", 1, Integer::sum);
               if (this.harborCode != null) {
                  this.facilityAssets.add(pier == null ? "pier" : pier.code());
               }
            }
            // ② 码头引道：码头必须真的接进村道网——否则"路网到码头就断了"，
            // 人在图上看到的是一条通往海边却半路消失的路。先修路、后盖房，
            // 房子再靠 canPlace 避开路面，就不会把引道堵死。
            // 顺序也要讲究：路在货场之前落地，货场才能按"引道最终从哪个方向下来"摆开——
            // 只按栈桥朝向摆，岸线一斜，铺装地就被路从中间斜切成两半。
            this.connectWharf(roads, rootX, rootZ);
            // ③ 岸上货场：栈桥只是伸进水里的那段木板，真实码头在岸上一定有一块硬地——
            // 卸货、堆货、系缆、等船都在那儿。没有它，栈桥就是"一根插进水里的木条"。
            // 岸线后面常常紧跟着礁石/陡坡/林子，所以不能只试一个位置：尺寸与位移都往下退。
            double[] quaySpot = this.placeQuay(pierName, rootX, rootZ, ux, uz, rot, pierLen,
                    pier == null ? null : pier.code(), roads);
            boolean hasQuay = quaySpot != null;
            if (!hasQuay) {
               this.bump("码头货场:放不下");
            }
            // ④ 仓栈 + 渔具棚：码头该有的两栋房子，门朝货场开。
            // 落位必须量「门到路的距离」——仓栈是码头唯一有人的房子，它离路四十米，
            // 图上就是"草地里孤零零一间仓房"，比不盖更假。
            double yardX = hasQuay ? quaySpot[0] : rootX;
            double yardZ = hasQuay ? quaySpot[1] : rootZ;
            int yard = this.placeHarborYard(assets, pierName, rootX, rootZ, yardX, yardZ, ux, uz,
                    hasQuay ? quaySpot[2] : (double)16.0F, hasQuay ? quaySpot[3] : (double)10.0F, roads);
            if (yard > 0) {
               this.diag.merge("码头仓栈", yard, Integer::sum);
            }

         } else {
            this.bump("无深水");
         }
      }
   }

   /**
    * 码头货场（岸上的铺装装卸场）：挑一块放得下的硬地落下去。
    *
    * <p>不能像建筑那样"绕着锚点找空地"：货场必须<b>贴着栈桥根</b>（人从桥上下来就是货场），
    * <p><b>朝向要按引道算，不能按栈桥算</b>：货场的长边与<b>引道的末段走向垂直</b>（也就是
    * 顺着岸线），再沿长边方向整体侧移半个宽度——于是引道从货场的一条短边旁下来、直达栈桥根，
    * 铺装地整块留在旁边。若按栈桥朝向摆、场地居中，路必然从场地中间穿过去，
    * 俯视图上就是"一块铺装地被斜着劈成两半"。</p>
    *
    * @param pierName      码头名（写进语义，供后续定位）
    * @param rootX         栈桥岸上根节点 X（米）
    * @param rootZ         栈桥岸上根节点 Z（米）
    * @param ux            栈桥方向单位向量 X（指向水面）；没有引道时按它兜底
    * @param uz            栈桥方向单位向量 Z
    * @param rot           栈桥朝向（度）
    * @param pierLen       栈桥长度（米，决定货场沿岸尺度）
    * @param pierAssetCode 栈桥的资产编码（写进货场的 assetCode，便于按资产查询）
    * @param roads         路网（判"引道会不会从货场中间穿过去"）
    * @return {中心X, 中心Z, 沿岸宽, 纵深}；放不下返回 null
    */
   private double[] placeQuay(String pierName, double rootX, double rootZ, double ux, double uz,
                              double rot, double pierLen, String pierAssetCode, List<RoadSpec> roads) {
      double baseW = Math.max((double)15.0F, Math.min((double)26.0F, pierLen * (double)0.66F));
      double[] depths = new double[]{12.0, 10.5, 9.0, 7.5, 6.5};
      double[] widths = new double[]{baseW, baseW * 0.82, baseW * 0.66F, baseW * 0.55F,
              baseW * 0.45F};
      double[] insets = new double[]{1.2, 3.6, 6.4, 9.5, 13.0, 17.0, 22.0};
      // 引道末段的走向（陆 → 桥根）：货场的长边与它垂直，短边正对它。
      double[] appr = this.wharfApproach(roads, rootX, rootZ);
      double dx;
      double dz;
      if (appr != null) {
         dx = appr[0];
         dz = appr[1];
      } else {
         // 兜底：引道没修成时按「聚落中心 → 桥根」当引道方向。
         dx = rootX - this.settleX;
         dz = rootZ - this.settleZ;
         double dl = Math.hypot(dx, dz);
         if (dl < (double)1.0F) {
            dx = ux;
            dz = uz;
         } else {
            dx /= dl;
            dz /= dl;
         }
      }

      // 长边方向 = 引道方向的法线 (cos q, sin q) = (-dz, dx) → q = atan2(dx, -dz)
      double qrot = Math.toDegrees(Math.atan2(dx, -dz));
      double nx = -dz;
      double nz = dx;
      // 「路从场地中间穿过去」是最刺眼的一种，但比"根本没有货场"轻：
      // 所以先按"不被路穿过"找，整圈都不行再退一步接受被穿过的那块——
      // 海峡、沙嘴、礁盘上的码头，岸上往往只有一条窄带，硬要求会让码头一块铺装地都没有。
      //
      // 降级时必须<b>比"穿过多少米"</b>，不能取循环里第一块：第一块是最大、最居中的那块，
      // 实测 14×12 米的货场被一条 3 米土径斜着劈成两半（穿场 13.7 米）；同样地形里换一块
      // 小一点、偏一点的，路只从角上擦过去。旧写法（取第一块）与渡口选址「首档即胜出」
      // 是同一类错误：把"第一个可选"当成"最合适的"。
      double[] crossed = null;
      double crossedPenalty = Double.MAX_VALUE;

      // 朝向优先按引道法线；整圈都放不下时再允许 ±8° 微转——岸边窄带上差这半个角，
      // 长边就能与压过来的那条街平行，路口从"切进场地"变成"贴着场地边过"。
      for(double drot : new double[]{(double)0.0F, (double)8.0F, (double)-8.0F}) {
         double qrot2 = qrot + drot;
         for(double inset : insets) {
            for(double depth : depths) {
               for(double w : widths) {
                  if (w < (double)8.0F || depth < (double)6.0F) {
                     continue;
                  }
                  // 侧移半个宽度 + 2 米：货场整块挪到引道一侧，短边贴着引道。
                  // 后两档（+4.5 / +7.5 米）是给"岸边窄带"留的：岸边被路压住时，把货场再往
                  // 侧后方挪一点就能让开路，代价只是离桥根远几米——比"引道劈开铺装地"轻得多。
                  double[] shifts = new double[]{w / (double)2.0F + (double)2.0F,
                          -(w / (double)2.0F + (double)2.0F),
                          w / (double)2.0F + (double)4.5F,
                          -(w / (double)2.0F + (double)4.5F),
                          w / (double)2.0F + (double)7.5F,
                          -(w / (double)2.0F + (double)7.5F)};
                  for(double shift : shifts) {
                     double back = -(depth / (double)2.0F + inset);
                     double cx = rootX + dx * back + nx * shift;
                     double cz = rootZ + dz * back + nz * shift;
                     double[][] poly = this.rect(cx, cz, w, depth, qrot2);
                     String why = this.quayBlocked(poly);
                     if (why != null) {
                        this.bump("货场:" + why);
                        continue;
                     }
                     // 引道从货场中间穿过去是同一缺陷的另一半：场地整块，路在边上。
                     if (this.roadCrossesYard(poly, roads)) {
                        this.bump("货场:被路穿过");
                        double penalty = this.crossingPenalty(poly, roads);
                        if (penalty < crossedPenalty) {
                           crossedPenalty = penalty;
                           crossed = new double[]{cx, cz, w, depth, qrot2};
                        }
                        continue;
                     }
                     return this.addQuay(pierName, pierAssetCode, rootX, rootZ, cx, cz, w, depth, qrot2);
                  }
               }
            }
         }
      }
      if (crossed != null) {
         this.diag.merge("货场:被路穿过(降级)", 1, Integer::sum);
         return this.addQuay(pierName, pierAssetCode, rootX, rootZ,
                 crossed[0], crossed[1], crossed[2], crossed[3], crossed[4]);
      }
      return null;
   }

   /**
    * 落一块货场（铺装、占位、要素、组、诊断一次写齐）。
    *
    * @param pierName      码头名
    * @param pierAssetCode 栈桥资产编码
    * @param rootX         栈桥岸上根节点 X（米）
    * @param rootZ         栈桥岸上根节点 Z（米）
    * @param cx            货场中心 X（米）
    * @param cz            货场中心 Z（米）
    * @param w             沿岸向宽度（米）
    * @param depth         垂直岸线向深度（米）
    * @param qrot          朝向（度）
    * @return {中心X, 中心Z, 宽, 深}
    */
   private double[] addQuay(String pierName, String pierAssetCode, double rootX, double rootZ,
                            double cx, double cz, double w, double depth, double qrot) {
      double[][] poly = this.rect(cx, cz, w, depth, qrot);
      this.rasterPoly(poly, 1.0F);
      this.features.add(new PlacedFeature(this.nextCode("f_quay"), pierName + "·货场", "水工", "quay",
              pierAssetCode, cx, cz, w, depth, qrot, 0.05, 1, this.elevationAt(cx, cz),
              (String)null, "#a49b8b", (String)null, rootX, rootZ, qrot, poly,
              "{\"harbor\":true,\"role\":\"quay\",\"owner\":\"" + pierName.replace("\"", "") + "\"}",
              "gate", 0, 3));
      this.groups.add(new PlacedGroup(this.nextCode("grp_quay"), pierName + "·货场", "compound",
              cx, cz, w / (double)2.0F, qrot, "harbor", "卸货、堆货、系缆"));
      this.diag.merge("码头货场", 1, Integer::sum);
      return new double[]{cx, cz, w, depth};
   }

   /**
    * 码头引道<b>末段的走向</b>（从陆侧指向栈桥根的单位向量）。
    *
    * <p>为什么要单独取：引道是先修的，货场是后摆的。货场的长边要与"路最后怎么下来"垂直，
    * 才能让路从场地边上过而不是从中间穿过去。用「聚落中心 → 桥根」这种大方向代替，
    * 遇到路在岸边拐个弯就差出三四十度——画出来仍是斜切。</p>
    *
    * @param roads 路网
    * @param rootX 栈桥岸上根节点 X（米）
    * @param rootZ 栈桥岸上根节点 Z（米）
    * @return 单位向量 {dx, dz}；没有码头引道（或它太短）返回 null
    */
   private double[] wharfApproach(List<RoadSpec> roads, double rootX, double rootZ) {
      List<double[]> best = this.wharfPath(roads, rootX, rootZ);
      if (best == null) {
         return null;
      }
      // 末段若太短（抽稀后常只剩两三米），再往前取一段，免得方向被一两个点的抖动带偏。
      int last = best.size() - 1;
      for(int back = 1; back < best.size() && back <= 3; ++back) {
         double[] a = best.get(last);
         double[] b = best.get(last - back);
         if (Math.hypot(a[0] - b[0], a[1] - b[1]) >= (double)8.0F) {
            double dx = a[0] - b[0];
            double dz = a[1] - b[1];
            double len = Math.hypot(dx, dz);
            return new double[]{dx / len, dz / len};
         }
      }
      return null;
   }

   /**
    * 引道（或任何路）会不会从货场中间穿过去。
    *
    * <p>判据取「路面中心线落进场内」加 1 米余量：路面半宽贴着场边是正常的（那就是沿岸的街），
    * 真正要禁的是中心线插进场里——那等于把一整块铺装地切成两半。</p>
    *
    * @param poly  货场多边形（米）
    * @param roads 路网
    * @return true=有路从场内穿过
    */
   private boolean roadCrossesYard(double[][] poly, List<RoadSpec> roads) {
      if (roads == null || roads.isEmpty() || poly == null || poly.length < 3) {
         return false;
      }
      // 多边形当作一圈闭合折线，用与"点到路"同一套几何量距离（MapNetwork.nearestOnPath）。
      List<double[]> rim = new ArrayList();
      for(double[] pt : poly) {
         rim.add(pt);
      }
      rim.add(poly[0]);
      double margin = (double)1.0F;
      for(RoadSpec r : roads) {
         List<double[]> p = r.pathMeters();
         if (p == null || p.size() < 2) {
            continue;
         }
         for(int i = 1; i < p.size(); ++i) {
            double[] a = p.get(i - 1);
            double[] b = p.get(i);
            double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, (int)Math.ceil(seg / (double)2.0F));
            for(int k = 0; k <= steps; ++k) {
               double t = (double)k / (double)steps;
               double px = a[0] + (b[0] - a[0]) * t;
               double pz = a[1] + (b[1] - a[1]) * t;
               if (pointInPoly(px, pz, poly)) {
                  return true;
               }
               if (MapNetwork.nearestOnPath(rim, px, pz)[2] < margin) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   /**
    * 货场被路穿过的"代价"：穿过长度 × 路宽权重（街 &gt; 巷 &gt; 土径）。
    *
    * <p>只在"整圈都被路穿过"的降级分支里用：此时没有干净的位置，只能挑一块最不刺眼的。
    * 代价 = Σ（某条路落在场地内的中心线长度 × max(1, 路宽)）。<b>不能取第一块</b>——
    * 循环第一块是最大、最居中的那块，被路斜切时观感最差（实测 13.7 米穿场）。</p>
    *
    * @param poly  货场多边形（米）
    * @param roads 路网
    * @return 代价；没有任何路穿过时返回 {@link Double#MAX_VALUE}
    */
   private double crossingPenalty(double[][] poly, List<RoadSpec> roads) {
      if (roads == null || roads.isEmpty() || poly == null || poly.length < 3) {
         return Double.MAX_VALUE;
      }
      double penalty = (double)0.0F;
      for(RoadSpec r : roads) {
         List<double[]> p = r.pathMeters();
         if (p == null || p.size() < 2) {
            continue;
         }
         double inside = (double)0.0F;
         for(int i = 1; i < p.size(); ++i) {
            double[] a = p.get(i - 1);
            double[] b = p.get(i);
            double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (seg < 1.0E-6) {
               continue;
            }
            int steps = Math.max(1, (int)Math.ceil(seg / (double)1.0F));
            for(int k = 0; k < steps; ++k) {
               double t = ((double)k + (double)0.5F) / (double)steps;
               double px = a[0] + (b[0] - a[0]) * t;
               double pz = a[1] + (b[1] - a[1]) * t;
               if (pointInPoly(px, pz, poly)) {
                  inside += seg / (double)steps;
               }
            }
         }
         if (inside > (double)0.01F) {
            penalty += inside * Math.max((double)1.0F, r.width());
         }
      }
      return penalty <= 0.0 ? Double.MAX_VALUE : penalty;
   }

   /**
    * 货场的落位校验：只看<b>不能铺</b>的东西（水面、陡坡/礁石），不看占用场。
    *
    * <p>为什么专门写一条：货场是铺装地面，<b>引道与街道压在货场上本来就是对的</b>——
    * 板车从村里一路压到栈桥根，货场当然被路穿过。用建筑那套 `占用` 判据，货场会
    * 因为"门口就是路"而永远放不下。</p>
    *
    * @param poly 货场多边形（米）
    * @return 不可铺的原因；可铺返回 null
    */
   private String quayBlocked(double[][] poly) {
      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minZ = Double.MAX_VALUE;
      double maxZ = -Double.MAX_VALUE;

      for(double[] pt : poly) {
         minX = Math.min(minX, pt[0]);
         maxX = Math.max(maxX, pt[0]);
         minZ = Math.min(minZ, pt[1]);
         maxZ = Math.max(maxZ, pt[1]);
      }

      int x0 = (int)Math.floor(minX / this.mpc);
      int x1 = (int)Math.ceil(maxX / this.mpc);
      int z0 = (int)Math.floor(minZ / this.mpc);
      int z1 = (int)Math.ceil(maxZ / this.mpc);
      int total = 0;
      int steep = 0;

      for(int z = z0; z <= z1; ++z) {
         for(int x = x0; x <= x1; ++x) {
            if (x < 1 || z < 1 || x >= this.size - 1 || z >= this.size - 1) {
               return "越界";
            }
            if (pointInPoly(((double)x + (double)0.5F) * this.mpc, ((double)z + (double)0.5F) * this.mpc, poly)) {
               int i = z * this.size + x;
               ++total;
               if (this.t.waterCell(i)) {
                  return "水面";
               }
               if ((double)this.t.buildable[i] < (double)0.01F) {
                  ++steep;
               }
            }
         }
      }

      if (total == 0) {
         return "空";
      }
      // 岸线附近总有一两格是礁石/沙滩陡坎，允许一成以内的格子铺不了
      return (double)steep / (double)total > (double)0.12F ? "不平" : null;
   }

   /**
    * 码头引道：把栈桥的岸上根节点接进村道网。
    *
    * <p><b>为什么必须有这一步</b>：栈桥是"伸进水里的那段木板"，它的根节点在岸上，
    * 而路网是在聚落里生成的——两者之间往往隔着十几到几十米。只画栈桥、不管引道，
    * 图上就是"一条路通往海边、却在半路消失"，人走到码头要么踩草地要么无路可走。</p>
    *
    * <p>引道按 {@link MapNetwork} 的最近点接驳（不是最近的**顶点**），并且走
    * {@link #linkClearOfBuildings}：直段会穿房就绕行，绕不出来宁可不修——码头没有引道
    * 是缺陷，压了人家房子是硬伤。</p>
    *
    * @param roads 路网（就地追加；码头引道的 code 前缀是 {@code wharf_}，参与后续整备）
    * @param rootX 栈桥岸上根节点 X（米）
    * @param rootZ 栈桥岸上根节点 Z（米）
    */
   private void connectWharf(List<RoadSpec> roads, double rootX, double rootZ) {
      if (roads == null || roads.isEmpty()) {
         this.bump("码头引道:无路网");
         return;
      }
      double[] hit = this.nearestRoadPoint(roads, rootX, rootZ);
      if (hit == null) {
         this.bump("码头引道:无路可接");
         return;
      }
      // 路网就在码头口（≤4 米）时不必再铺引道：这不是"没连上"，而是村道本来就走到了桥头
      // （三颗种子里引道长度都是 0~2 米——再铺一段就是在路口描一个多余的疙瘩）。
      if (hit[2] <= (double)4.0F) {
         this.diag.merge("码头引道:村道已到桥头", 1, Integer::sum);
         return;
      }
      List<double[]> path;
      if (hit[2] <= (double)8.0F) {
         path = new ArrayList();
         path.add(new double[]{hit[0], hit[1]});
         path.add(new double[]{rootX, rootZ});
      } else {
         path = this.linkClearOfBuildings(hit[0], hit[1], rootX, rootZ, (String)null);
      }
      if (path == null || path.size() < 2) {
         this.bump("码头引道:不通");
         return;
      }
      path.set(0, new double[]{hit[0], hit[1]});
      path.set(path.size() - 1, new double[]{rootX, rootZ});
      // 引道是从村道直连桥头的，而货场就摆在桥头的一侧——直连在近一半的种子里会斜切货场
      // （实测 24 颗确定性种子 11 颗如此，俯视图上就是"一整块铺装地被引道劈成两半"）。
      // 根因：货场是 S4 早段落的水工物，引道是 S4 收尾按定稿路网重铺的，两边互不知道对方。
      // 既然引道可以重铺，就让它绕：把铺装地格子加价后重新寻路，绕不开才退回原路。
      if (this.pathCrossesPaved(path)) {
         List<double[]> around = this.routeAroundPaved(hit[0], hit[1], rootX, rootZ);
         if (around != null && around.size() >= 2
                 && MapNetwork.length(around) <= Math.max((double)60.0F,
                         MapNetwork.length(path) * (double)4.0F)) {
            path = around;
            this.diag.merge("码头引道:绕开铺装地", 1, Integer::sum);
         } else {
            this.diag.merge("码头引道:绕不开铺装地", 1, Integer::sum);
         }
      }
      List<double[]> thin = RoadRouter.simplify(path, (double)1.2F);
      if (thin.size() < 2) {
         return;
      }
      RoadSpec spec = new RoadSpec(this.nextCode("wharf"), "码头引道", "lane", RoadGrade.VILLAGE,
              (double)3.6F, "stone", thin, 0);
      roads.add(spec);
      this.blockRoads(List.of(spec));
      this.diag.merge("码头引道", 1, Integer::sum);
   }

   /**
    * 一条折线是否穿过任何一块铺装地（广场/货场）。
    *
    * <p>与 {@link #segmentCrossesPaved} 同口径（严格相交 + 端点落在场内），
    * 只是按折线逐段判——码头引道重铺时用它决定"要不要绕"。</p>
    *
    * @param path 折线（米）
    * @return true=穿过了铺装地
    */
   private boolean pathCrossesPaved(List<double[]> path) {
      if (path == null || path.size() < 2) {
         return false;
      }
      for(int i = 1; i < path.size(); ++i) {
         double[] a = path.get(i - 1);
         double[] b = path.get(i);
         if (this.segmentCrossesPaved(a[0], a[1], b[0], b[1])) {
            return true;
         }
      }
      return false;
   }

   /**
    * 绕开铺装地把两个点连起来（码头引道专用）。
    *
    * <p>做法：拿 {@link #costWithClearance()} 的代价场，把铺装地格子加价 600 后重新 A*——
    * 绕行代价远低于压场，寻路自然会贴着场地边找缝走。</p>
    *
    * <p><b>为什么按"加价范围"从 0 米试到 1.6 米</b>：加价范围如果一上来就把场地外缘也圈进去，
    * 贴着场地边的那条缝也会被加价，"沿边绕"反而比"从中间穿过去"更贵，A* 就会选穿场。
    * 实测就是这样：直接给 1.5 米外缘加价，绕行结果仍是斜穿 10.5 米。先只给场地内部加价，
    * 让 A* 贴着边走；万一贴边走的结果仍擦角（1 米格的对角步会切角），再逐档外扩重试。</p>
    *
    * @param x1 起点 X（米）
    * @param z1 起点 Z（米）
    * @param x2 终点 X（米）
    * @param z2 终点 Z（米）
    * @return 不穿铺装的折线；绕不出来返回 {@code null}
    */
   private List<double[]> routeAroundPaved(double x1, double z1, double x2, double z2) {
      if (this.features.isEmpty()) {
         return null;
      }
      double mpc = Math.max(0.05, this.mpc);
      int gx0 = (int)Math.round(x1 / mpc);
      int gz0 = (int)Math.round(z1 / mpc);
      int gx1 = (int)Math.round(x2 / mpc);
      int gz1 = (int)Math.round(z2 / mpc);
      for(double margin : new double[]{(double)0.0F, (double)0.8F, (double)1.6F}) {
         float[] cost = this.costWithClearance();
         boolean any = false;
         for(PlacedFeature f : this.features) {
            if (!"plaza".equals(f.kind()) && !"quay".equals(f.kind())) {
               continue;
            }
            double[][] poly = f.polygon();
            if (poly == null || poly.length < 3) {
               continue;
            }
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for(double[] pt : poly) {
               minX = Math.min(minX, pt[0]);
               maxX = Math.max(maxX, pt[0]);
               minZ = Math.min(minZ, pt[1]);
               maxZ = Math.max(maxZ, pt[1]);
            }
            int cx0 = (int)Math.floor((minX - margin) / mpc);
            int cx1 = (int)Math.ceil((maxX + margin) / mpc);
            int cz0 = (int)Math.floor((minZ - margin) / mpc);
            int cz1 = (int)Math.ceil((maxZ + margin) / mpc);
            for(int z = cz0; z <= cz1; ++z) {
               for(int x = cx0; x <= cx1; ++x) {
                  if (x < 0 || z < 0 || x >= this.size || z >= this.size) {
                     continue;
                  }
                  double px = ((double)x + (double)0.5F) * mpc;
                  double pz = ((double)z + (double)0.5F) * mpc;
                  if (this.distToPoly(px, pz, poly) <= margin) {
                     cost[z * this.size + x] += 600.0F;
                     any = true;
                  }
               }
            }
         }
         if (!any) {
            return null;
         }
         RoadRouter.Route r = RoadRouter.route(this.t, cost, gx0, gz0, gx1, gz1, (double)0.8F);
         if (r == null || r.path().size() < 2) {
            continue;
         }
         List<double[]> out = new ArrayList();
         for(double[] pt : r.path()) {
            out.add(new double[]{pt[0] * mpc, pt[1] * mpc});
         }
         out.set(0, new double[]{x1, z1});
         out.set(out.size() - 1, new double[]{x2, z2});
         if (!this.pathCrossesPaved(out)) {
            return out;
         }
      }
      return null;
   }

   /**
    * 点到多边形边界的最短距离（点在多边形内时返回 0）。
    *
    * @param px 点 X（米）
    * @param pz 点 Z（米）
    * @param poly 多边形（米）
    * @return 距离（米）
    */
   private double distToPoly(double px, double pz, double[][] poly) {
      if (pointInPoly(px, pz, poly)) {
         return (double)0.0F;
      }
      double best = Double.MAX_VALUE;
      for(int i = 0; i < poly.length; ++i) {
         double[] a = poly[i];
         double[] b = poly[(i + 1) % poly.length];
         double dx = b[0] - a[0];
         double dz = b[1] - a[1];
         double l2 = dx * dx + dz * dz;
         double t = l2 < 1.0E-12 ? (double)0.0F
                 : Math.max((double)0.0F, Math.min((double)1.0F,
                         ((px - a[0]) * dx + (pz - a[1]) * dz) / l2));
         double d = Math.hypot(px - a[0] - t * dx, pz - a[1] - t * dz);
         best = Math.min(best, d);
      }
      return best;
   }

   /**
    * 按<b>定稿路网</b>重新接一遍码头引道。
    *
    * <p>为什么必须重接：引道是在排房阶段（码头刚落位时）修的，那之后路网还要经历剪断头路、
    * 剪碎路、剪无人用尾段、去重、缝合六七道整备——海边那一截正是"越靠端头越容易被判成没人用"
    * 的地方，被剪掉之后就是图上那条「村道走到离海岸还有几十米就停了，栈桥孤零零伸在水里」。
    * 判据不能只靠各道剪枝记住码头（它们各有各的服务口径），只能在这里按最终几何兜底重接：</p>
    * <ol>
    *   <li>把已有的码头引道全部丢掉（它的几何已经过期）；</li>
    *   <li>按栈桥根节点重接一次，接头取最近的路面点。</li>
    * </ol>
    *
    * @param net 定稿路网（就地追加；code 前缀仍是 {@code wharf_}）
    */
   private void reconnectWharf(List<RoadSpec> net) {
      if (net == null) {
         return;
      }
      for(PlacedFeature f : this.features) {
         if (!"pier".equals(f.kind())) {
            continue;
         }
         String sem = f.semanticJson();
         if (sem != null && sem.contains("\"head\":true")) {
            continue;   // 桥头那一段不是栈桥本体，它的"门"是借栈桥根写的
         }
         net.removeIf(r -> r.code() != null && r.code().startsWith("wharf_"));
         this.connectWharf(net, f.doorX(), f.doorZ());
         // 仓栈/渔具棚是在"当时的引道"旁边摆的，引道按定稿路网重铺后可能挪开几米——
         // 实测有一粒种子仓栈门口到新引道 8.17 米、且门朝的是旧引道方向，
         // 收尾的入户小径因"路在门背后"补不上。这里按定稿几何兜底：
         // 门离路超过 7 米就补一条 1.6 米宽的仓栈小径（绕过建筑，不穿墙）。
         this.connectHarborShedDoors(net);
         this.diag.merge("码头重接", 1, Integer::sum);
         return;   // 一座码头只接一次
      }
   }

   /**
    * 码头仓栈/渔具棚门口补小径（按定稿引道兜底）。
    *
    * <p>为什么必须补：仓栈是早段落的位，引道是收尾按定稿路网重铺的——两边几何会错开几米。
    * 门口离路 7 米以内算"临院"，超过就得有条小径，否则图上就是一栋"门开向草地"的仓库。
    * 只处理 {@code harbor:true} 的生产房（民居/设施的门由入户小径那一步统一收口）。</p>
    *
    * @param net 定稿路网（就地追加；code 前缀仍是 {@code door_}，与入户小径同族）
    */
   private void connectHarborShedDoors(List<RoadSpec> net) {
      if (net == null || net.isEmpty()) {
         return;
      }
      for(PlacedFeature f : this.features) {
         if (!"building".equals(f.kind())) {
            continue;
         }
         String sem = f.semanticJson();
         if (sem == null || !sem.contains("\"harbor\":true")) {
            continue;
         }
         double[] hit = this.nearestRoadPoint(net, f.doorX(), f.doorZ());
         if (hit == null) {
            continue;
         }
         if (hit[2] <= (double)7.0F) {
            continue;
         }
         if (hit[2] > (double)30.0F) {
            this.bump("仓栈门:离路太远");
            continue;
         }
         // 起点要放到门外：门在墙上，直接连"门背后"的路会从屋里穿过去
         // （实测一颗种子里仓栈被自己那条小径压了 2 米）。
         double rad = Math.toRadians(f.doorDir());
         double startX = f.doorX() + Math.cos(rad) * (double)2.0F;
         double startZ = f.doorZ() + Math.sin(rad) * (double)2.0F;
         List<double[]> path = this.linkClearOfBuildings(startX, startZ, hit[0], hit[1], f.code());
         if (path == null || path.size() < 2) {
            this.bump("仓栈门:补不成");
            continue;
         }
         path.set(0, new double[]{startX, startZ});
         path.add(0, new double[]{f.doorX(), f.doorZ()});
         // 除"门口到门外的第一小段"外，小径不许压任何建筑（自己的也算）。
         boolean blocked = false;
         for(int i = 1; i < path.size(); ++i) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            String ignore = i == 1 ? f.code() : null;
            if (!this.clearOfBuildings(a[0], a[1], b[0], b[1], ignore, false)) {
               blocked = true;
               break;
            }
         }
         if (blocked) {
            this.bump("仓栈门:补不成");
            continue;
         }
         RoadSpec spec = new RoadSpec(this.nextCode("door"), "仓栈小径", "path", RoadGrade.TRACK,
                 (double)1.6F, "dirt", path, 0);
         net.add(spec);
         this.blockRoads(List.of(spec));
         this.diag.merge("仓栈门:补小径", 1, Integer::sum);
      }
   }

   /**
    * 码头岸上的两栋房子：仓栈（大）与渔具棚（小），门都朝引道/货场开。
    *
    * <p>真实码头的岸上永远有仓栈和渔具棚——货要过夜、网要晾、桨要收。只画一条栈桥加一块铺装地，
    * 读起来仍是"空地中间插了块木板"。两栋房子按 {@code 生产} 类资产落位，语义里带
    * {@code harbor/role}，日程与对话都能据此定位到"码头仓栈"。</p>
    *
    * <p><b>为什么优先沿引道找位，而不是围着货场中心转</b>：货场是铺装地，本身就占掉岸上唯一的平地，
    * 围着它找空位，剩下的一圈往往全是礁石、陡坎和水（实测四颗种子里有三颗一栋也放不下）；
    * 而引道是<b>必然存在且必然在能走的陆地上</b>的，沿它两侧退让，房子一定临路、一定在干地上。
    * 围着货场中心那套留作兜底。</p>
    *
    * @param assets   资产类型库
    * @param pierName 码头名（写进语义，供后续定位；不含任何具体地名）
    * @param rootX    栈桥岸上根节点 X（米）
    * @param rootZ    栈桥岸上根节点 Z（米）
    * @param cx       货场中心 X（米）
    * @param cz       货场中心 Z（米）
    * @param ux       栈桥方向单位向量 X（指向水面）
    * @param uz       栈桥方向单位向量 Z
    * @param qw       货场沿岸方向宽度（米）
    * @param qd       货场垂直岸线方向深度（米）
    * @param roads    路网（用来量「仓栈门到路」的距离）
    * @return 实际落了几栋
    */
   private int placeHarborYard(List<AssetSpec> assets, String pierName, double rootX, double rootZ,
                               double cx, double cz, double ux, double uz, double qw, double qd,
                               List<RoadSpec> roads) {
      AssetSpec spec = this.pickByCategory(assets, "生产");
      if (spec == null) {
         spec = this.pickByCategory(assets, "民居");
      }
      if (spec == null) {
         return 0;
      }
      int placed = 0;
      String[] roles = new String[]{"warehouse", "shed"};
      String[] names = new String[]{"仓栈", "渔具棚"};
      double span = (double)this.size * this.mpc;
      // 沿哪条路摆：优先码头引道；但村道本来就修到桥头时引道只有零两米（
      // 那种情况下"沿引道"没有空间），改沿<b>最近的那条路</b>的末段摆——它就是进出码头的路。
      List<double[]> access = this.wharfPath(roads, rootX, rootZ);
      if (access == null || MapNetwork.length(access) < (double)6.0F) {
         List<double[]> host = this.nearestRoadPath(roads, rootX, rootZ);
         if (host != null) {
            access = host;
         }
      }

      for(int k = 0; k < roles.length; ++k) {
         double w = spec.baseW() * (k == 0 ? (double)1.0F : (double)0.6F);
         double d = spec.baseD() * (k == 0 ? (double)1.0F : (double)0.55F);
         if (k == 1) {
            w = Math.max((double)3.6F, Math.min((double)5.4F, w));
            d = Math.max((double)3.0F, Math.min((double)4.2F, d));
         }
         boolean found = false;
         double bx = (double)0.0F;
         double bz = (double)0.0F;
         double brot = (double)0.0F;

         // ① 沿进出码头的那条路两侧退让：门就开向路。这里要在候选圈里挑**门口离路最近**的，
         // 不能取第一块能放下的——第一块可能落在"门朝院内、背对引道"的一侧，
         // 收尾一量门口离路 8 米（实测一颗种子正是如此），入户小径又因为"路在门背后"补不上。
         if (access != null) {
            double bestDoor = Double.MAX_VALUE;
            double bx1 = (double)0.0F;
            double bz1 = (double)0.0F;
            double brot1 = (double)0.0F;
            boolean have = false;
            for(double back = 7.0; back <= (double)36.0; back += (double)3.5F) {
               double[] on = this.backAlongPath(access, back);
               if (on == null) {
                  break;
               }
               double nx = -on[3];
               double nz = on[2];
               for(double side = -1.0; side <= 1.0; side += 2.0) {
                  // 引道本身占着 occ（半宽 + 0.4 米），货场又拿走了另一侧；
                  // 先按 3.8 米净距摆，放不下再往外让——贴着 occ 边界摆会被判成"占用"。
                  for(double gap = (double)3.8F; gap <= (double)7.4F; gap += (double)1.8F) {
                     double off = gap + w / (double)2.0F;
                     double x = on[0] + nx * side * off;
                     double z = on[1] + nz * side * off;
                     if (x < (double)4.0F || z < (double)4.0F
                             || x > span - (double)4.0F || z > span - (double)4.0F) {
                        continue;
                     }
                     // 门朝引道：旋转角按"门前法线指向路点"解出来（与设施落位同一套公式）
                     double rot = Math.toDegrees(Math.atan2(-(on[0] - x), on[1] - z));
                     if (!this.canPlace(x, z, w, d, rot, 0.05F)) {
                        continue;
                     }
                     double rad0 = Math.toRadians(rot);
                     double doorX = x - Math.sin(rad0) * (d / (double)2.0F);
                     double doorZ = z + Math.cos(rad0) * (d / (double)2.0F);
                     double[] q = this.nearestRoadPoint(roads, doorX, doorZ);
                     double dr = q == null ? Double.MAX_VALUE : q[2];
                     if (dr < bestDoor) {
                        bestDoor = dr;
                        bx1 = x;
                        bz1 = z;
                        brot1 = rot;
                        have = true;
                     }
                  }
               }
            }
            // 26 米是硬上限（再远就是野地里的一间空房），超了留给 ② 再碰一次运气
            if (have && bestDoor <= (double)26.0F) {
               bx = bx1;
               bz = bz1;
               brot = brot1;
               found = true;
            }
         }

         // ② 兜底：围着货场中心找（引道没修成、或两岸都放不下时）。
         if (!found) {
            double[] backs = new double[]{8.0, 11.0, 14.0, 17.0, 20.0, 24.0};
            double[] sides = new double[]{-1.0, 1.0, 0.0, -2.0, 2.0};
            double bestDoor = Double.MAX_VALUE;
            for(double back : backs) {
               for(double side : sides) {
                  double lat = side * (qw / (double)2.0F + 4.5 + w / (double)2.0F);
                  double x = cx - ux * back - uz * lat;
                  double z = cz - uz * back + ux * lat;
                  if (x < (double)4.0F || z < (double)4.0F || x > span - (double)4.0F || z > span - (double)4.0F) {
                     continue;
                  }
                  double rot = Math.toDegrees(Math.atan2(-(cx - x), cz - z));
                  if (!this.canPlace(x, z, w, d, rot, 0.05F)) {
                     continue;
                  }
                  double rad0 = Math.toRadians(rot);
                  double[] q = this.nearestRoadPoint(roads,
                          x - Math.sin(rad0) * (d / (double)2.0F),
                          z + Math.cos(rad0) * (d / (double)2.0F));
                  double dr = q == null ? Double.MAX_VALUE : q[2];
                  if (dr < bestDoor) {
                     bestDoor = dr;
                     bx = x;
                     bz = z;
                     brot = rot;
                     found = true;
                  }
               }
            }
         }

         if (!found) {
            this.bump("码头仓栈:无空位");
            continue;
         }
         // 门口到路必须在 26 米内：再远就不盖了（宁可少一栋房子，也不要在野地里放一间没人去的空房）。
         // 26 米仍留给收尾的入户小径去接——它的候选接头只取 30 米内的路面点。
         double rad = Math.toRadians(brot);
         double doorX = bx - Math.sin(rad) * (d / (double)2.0F);
         double doorZ = bz + Math.cos(rad) * (d / (double)2.0F);
         double[] snap = this.nearestRoadPoint(roads, doorX, doorZ);
         double doorDist = snap == null ? Double.MAX_VALUE : snap[2];
         if (doorDist > (double)26.0F) {
            this.bump("码头仓栈:离路太远");
            continue;
         }
         if (doorDist > (double)7.0) {
            this.diag.merge("码头仓栈:偏远", 1, Integer::sum);
         }
         this.commit(bx, bz, w, d, brot);
         String sem = "{\"harbor\":true,\"role\":\"" + roles[k] + "\",\"owner\":\""
                 + pierName.replace("\"", "") + "\"}";
         this.features.add(new PlacedFeature(this.nextCode("f_harbor"), names[k], spec.category(),
                 "building", spec.code(), bx, bz, w, d, brot, k == 0 ? 4.2 : 2.6, 1, this.elevationAt(bx, bz),
                 spec.wallColor(), spec.roofColor(), spec.roofShape(), doorX, doorZ, brot,
                 this.rect(bx, bz, w, d, brot), sem, (String)null, 0, 2));
         ++placed;
      }
      return placed;
   }

   /**
    * 路网上"从末端往回走 {@code back} 米"的那个点，以及该处切向。
    *
    * @param path 折线（米）
    * @param back 距离末端的米数
    * @return {x, z, dirX, dirZ}；路径太短返回 null
    */
   private double[] backAlongPath(List<double[]> path, double back) {
      if (path == null || path.size() < 2) {
         return null;
      }
      double acc = (double)0.0F;
      for(int i = path.size() - 1; i >= 1; --i) {
         double[] a = path.get(i - 1);
         double[] b = path.get(i);
         double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
         if (seg < 1.0E-6) {
            continue;
         }
         if (acc + seg >= back) {
            double t = (back - acc) / seg;
            return new double[]{b[0] + (a[0] - b[0]) * t, b[1] + (a[1] - b[1]) * t,
                    (b[0] - a[0]) / seg, (b[1] - a[1]) / seg};
         }
         acc += seg;
      }
      double[] a = path.get(0);
      double[] b = path.get(1);
      double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
      if (seg < 1.0E-6) {
         return null;
      }
      return new double[]{a[0], a[1], (b[0] - a[0]) / seg, (b[1] - a[1]) / seg};
   }

   /**
    * 离栈桥根最近的那条码头引道（{@code wharf_}）。
    *
    * @param roads 路网
    * @param rootX 栈桥岸上根节点 X（米）
    * @param rootZ 栈桥岸上根节点 Z（米）
    * @return 折线；没有引道返回 null
    */
   private List<double[]> wharfPath(List<RoadSpec> roads, double rootX, double rootZ) {
      if (roads == null) {
         return null;
      }
      List<double[]> best = null;
      double bestD = Double.MAX_VALUE;
      for(RoadSpec r : roads) {
         String code = r.code() == null ? "" : r.code();
         if (!code.startsWith("wharf_")) {
            continue;
         }
         List<double[]> p = r.pathMeters();
         if (p != null && p.size() >= 2) {
            double d = MapNetwork.nearestOnPath(p, rootX, rootZ)[2];
            if (d < bestD) {
               bestD = d;
               best = p;
            }
         }
      }
      return best;
   }

   /**
    * 离给定点<b>最近的那条路</b>的折线（不限编码）。
    *
    * <p>码头用得上：村道本来就修到桥头时，引道退化成零两米，"沿引道摆房子"没有空间，
    * 得改成沿"进出码头的那条路"摆。</p>
    *
    * @param roads 路网
    * @param x     点 X（米）
    * @param z     点 Z（米）
    * @return 折线；路网为空返回 null
    */
   private List<double[]> nearestRoadPath(List<RoadSpec> roads, double x, double z) {
      if (roads == null) {
         return null;
      }
      List<double[]> best = null;
      double bestD = Double.MAX_VALUE;
      for(RoadSpec r : roads) {
         List<double[]> p = r.pathMeters();
         if (p != null && p.size() >= 2) {
            double d = MapNetwork.nearestOnPath(p, x, z)[2];
            if (d < bestD) {
               bestD = d;
               best = p;
            }
         }
      }
      return best;
   }

   /**
    * 剪掉「伸出聚落之后还在野地里跑」的路尾。
    *
    * <p><b>为什么必须剪</b>：真实路网的两端一定有落点——人家的院门、地头、河口、渡口。一条 500 米长的
    * 「礁丛小径」横穿全岛、两头都是草，是程序化地图最典型的假。这里只剪 <b>path/trail/lane/alley</b>
    * （田埂土路不剪：田埂本来就穿田而过），而且只剪「自由端」——末端 14 米内没有建筑、也不与其他路相接，
    * 就把这一段收掉；但不能把路剪到 30 米以下（那是一条真正有用的连接路）。</p>
    *
    * @param net 路网（就地替换元素）
    */
   /**
    * 路网去重：同一条路如果被两条 {@link RoadSpec} 各画了一遍，只保留"更像主干"的那条。
    *
    * <p><b>为什么会出现重复路</b>：AI 的拓扑边在镇口与程序化骨架重合、S4 的下地土路紧贴着
    * 已有的村道走、{@code MapNetwork.connect} 补的连线可能正好沿另一条路铺——三者都会产生
    * 「两条路几乎完全重合」的情况。后果不只是白画：占位场被压两遍（路面变宽）、
    * 渲染时两层半透明路肩叠加（出现一条比其他路更亮/更灰的"怪路"），
    * 图上就是用户说的「两种道路重叠到一起」。</p>
    *
    * <p>判据是几何重合率：一条路的采样点里有 62% 落在另一条已保留道路的 1.6 米内，就判重复。
    * 保留顺序是「骨架优先 → 等级高优先 → 更长优先」，所以丢掉的总是支线/土路，不会丢正街。</p>
    *
    * @param roads 候选路网
    * @return 去重后的路网
    */
   private List<RoadSpec> dedupeRoads(List<RoadSpec> roads) {
      List<RoadSpec> ordered = new ArrayList(roads);
      ordered.sort((a, b) -> {
         boolean sa = isSkeleton(a);
         boolean sb = isSkeleton(b);
         if (sa != sb) {
            return sa ? -1 : 1;
         }
         int g = Integer.compare(b.grade(), a.grade());
         if (g != 0) {
            return g;
         }
         return Double.compare(pathLength(b.pathMeters()), pathLength(a.pathMeters()));
      });
      List<RoadSpec> kept = new ArrayList();
      List<List<double[]>> lines = new ArrayList();
      List<Integer> grades = new ArrayList();
      int dropped = 0;
      int parallel = 0;
      for(RoadSpec r : ordered) {
         List<double[]> path = r.pathMeters();
         if (path == null || path.size() < 2) {
            continue;
         }
         // 「同走向重复路」才是真正常见的那一类：官道旁边几米再画一条土路，两条平行几十上百米。
         // 旧口径只抓「几乎贴在一起」（1.6 米内占 62%），平行偏移 2~4 米的完全抓不到。
         // 这里按「候选路 vs 每条已保留路」分别算覆盖率，判定条件带等级：
         //   · 已保留路等级更高 → 容差 3.6 米、覆盖率 55% 即丢（高等级压住低等级，符合"官道在上"）
         //   · 等级相同 → 容差 2.2 米、覆盖率 75% 才算重复（避免误删平行的两条巷道）
         // 田头路/下地土路例外：它们是田块唯一的接入段，只在 2.2 米内才算重复，免得把田弄成孤岛。
         // 这几类路不参与「平行去重」的激进判定：
         //   · farm_head / farm_link：田块唯一的接入段（只放宽，不豁免）
         //   · conn_ / farm_conn_ / wharf_：**连接型道路**——它们天生就是贴着别的路走的，
         //     一丢就把路网拆成分量（收尾补的连接路一旦被去重掉，分量就从 1 变 2），
         //     码头引道被去重掉就是"码头没有路"。这几类直接豁免：存在理由就是"把两块接起来"。
         String code = r.code();
         boolean neverDrop = isLinkRoad(code);
         boolean protectedFarm = code != null
                 && (code.startsWith("farm_head") || code.startsWith("farm_link"));
         if (neverDrop) {
            kept.add(r);
            lines.add(path);
            grades.add(r.grade());
            continue;
         }
         // 判定用 {@link MapNetwork#coverage}：候选路的采样点有多大比例压在已保留路上。
         // 旧实现是"每条路各算一遍 two 个计数器"，阈值只能给一个死数（3.6 米 / 55%），
         // 于是"重叠 51% 的同走向长路"刚好漏过去——实测 54 号图里镇上到东岸的两条路
         // 重合两百米仍被留下。现在覆盖率与容差都由等级决定，且和 S3 的目的地路去重同一套口径。
         boolean dup = false;
         boolean byHigher = false;
         for(int k = 0; k < lines.size() && !dup; ++k) {
            int kg = (Integer)grades.get(k);
            if (kg < r.grade()) {
               continue;
            }
            double tol;
            double need;
            if (kg > r.grade()) {
               // 高等级路压住同走向的低等级路：官道下面不该再有一条平行土路
               tol = protectedFarm ? 2.6 : 5.0;
               need = protectedFarm ? 0.80 : 0.45;
            } else {
               // 同级重复：容差收紧，避免误删两条真的并排的巷道
               tol = 3.2;
               need = 0.62;
            }
            // 判「同走向」（带方向）：只按距离判会把 T 字口的垂直进道当成平行重复路删掉，
            // 于是"门前那条小路"整条消失。真实世界里平行的两条路才是重复，垂直相交是路口。
            // 再加一条「同两端」判据：两条路连的是同样两个地方（镇口→渡口），中间怎么弯不重要
            // ——短路上局部切向随折点乱跳，只按方向会放过压在一起的一对（实测 19 米 + 23 米两条）。
            boolean dupHere = MapNetwork.coverage(path, (List)lines.get(k), tol, 4.0, 0.86) > need
                    || (MapNetwork.endsWithin(path, (List)lines.get(k), 12.0)
                    && MapNetwork.coverage(path, (List)lines.get(k), tol, 4.0, -1.0) > 0.50);
            if (dupHere) {
               dup = true;
               byHigher = kg > r.grade();
            }
         }
         if (dup) {
            if (byHigher) {
               ++parallel;
            } else {
               ++dropped;
            }
            continue;
         }
         kept.add(r);
         lines.add(path);
         grades.add(r.grade());
      }
      if (dropped > 0) {
         this.diag.merge("路去重", dropped, Integer::sum);
      }
      if (parallel > 0) {
         this.diag.merge("平行路去重", parallel, Integer::sum);
      }
      return kept;
   }

   /**
    * 剪掉「白修的路」：从路网里伸出去、尽头既没有房子也没有田，沿途也什么都没有的支线。
    *
    * <p><b>为什么必须剪</b>：这些路在数据上"连通"，在图上却是一条穿过草坡/林子、两头空空的
    * 土路——用户看到的就是「多余的道路」。它们通常来自三处：AI 拓扑里多给的目的地、
    * 补线（{@code MapNetwork.connect}）的残留、以及地貌阻挡导致目的地偏移后留下的半截。
    * 只剪叶枝（一端接网、一端悬空）且沿线 18 米内无房、14 米内无田的路，因此不会拆散路网。</p>
    *
    * @param net 路网（就地修改）
    */
   private void pruneDeadSpurs(List<RoadSpec> net) {
      List<RoadSpec> out = new ArrayList();
      int dropped = 0;
      for(RoadSpec r : net) {
         List<double[]> path = r.pathMeters();
         // 只处理非骨架、非栈桥的路：正街/后街/横巷是村镇形态的主体，不参与剪枝。
         // 等级不作为豁免条件——地图数据给的"官道"完全可能是一条二十几米的半截路：
         // 一端接街、另一端悬在荒野上空，什么也不服务；只因为它叫"主街"就留下，
         // 正是用户说的"多余的道路"。
         if (isSkeleton(r) || "pier".equals(r.kind()) || isLinkRoad(r.code())
                 || path == null || path.size() < 2) {
            out.add(r);
            continue;
         }
         boolean e0 = touchesOtherRoad(net, r, (double[])path.get(0));
         boolean e1 = touchesOtherRoad(net, r, (double[])path.get(path.size() - 1));
         if (e0 && e1) {
            out.add(r);
            continue;
         }
         boolean active = false;
         for(int i = 0; i < path.size(); i += 2) {
            double[] pt = (double[])path.get(i);
            if (this.hasBuildingNear(pt[0], pt[1], 18.0) || this.hasFieldNear(pt[0], pt[1], 14.0)) {
               active = true;
               break;
            }
         }
         double len = pathLength(path);
         if (!active && len > 20.0) {
            ++dropped;
            continue;
         }
         out.add(r);
      }
      if (dropped > 0) {
         net.clear();
         net.addAll(out);
         this.diag.merge("剪无用路", dropped, Integer::sum);
      }
   }

   /**
    * 剪掉「两头都不挨着」的悬空碎片路。
    *
    * <p><b>为什么会存在这种路</b>：S3 的目的地路是按 AI 给的节点各修一条的，节点落在海湾、
    * 礁石、林缘这种"没有聚落"的地方时，修出来的路两头都差着十几米没接上——俯视图上就是
    * 一段凭空浮在草地上的土路（用户说的「道路生成的这么碎」里，最刺眼的就是这一类）。</p>
    *
    * <p><b>为什么不直接吸附</b>：吸附最大也只到二十来米，而且两头顶着房子时不该硬拉；
    * 更关键的是这种碎片路本身没有服务对象——它既不通房子也不通田。剪掉它，比接上它更对。
    * 有房子/田挨着的碎片一律保留，交给收尾的连接路去接（那种情况说明它是真的入户路）。</p>
    *
    * @param net 路网（就地修改）
    */
   private void pruneOrphans(List<RoadSpec> net) {
      List<RoadSpec> out = new ArrayList();
      int dropped = 0;
      for(RoadSpec r : net) {
         List<double[]> path = r.pathMeters();
         if (path == null || path.size() < 2 || isSkeleton(r) || "pier".equals(r.kind())
                 || "connector".equals(r.kind())) {
            out.add(r);
            continue;
         }
         String code = r.code() == null ? "" : r.code();
         if (isLinkRoad(code)) {
            out.add(r);
            continue;
         }
         boolean e0 = touchesOtherRoad(net, r, path.get(0));
         boolean e1 = touchesOtherRoad(net, r, path.get(path.size() - 1));
         if (e0 || e1) {
            out.add(r);
            continue;
         }
         boolean serves = false;
         for(int i = 0; i < path.size() && !serves; i += 2) {
            double[] pt = (double[])path.get(i);
            serves = this.hasBuildingNear(pt[0], pt[1], 18.0) || this.hasFieldNear(pt[0], pt[1], 14.0);
         }
         if (serves) {
            out.add(r);
            continue;
         }
         ++dropped;
      }

      if (dropped > 0) {
         net.clear();
         net.addAll(out);
         this.diag.merge("剪悬空路", dropped, Integer::sum);
      }
   }


   /**
    * 把每条路的「没人用的尾巴」剪掉：只保留有服务对象的<b>连续段</b>。
    *
    * <p><b>为什么必须有这一步</b>：S3 的目的地路是冲着一个<b>语义</b>（雾隐林、灯塔、盐滩）修的，
    * 而那个地标真正落在哪儿是 S4 的 {@code placeAnchoredFacilities} 决定的——两者常常差四五十米，
    * 于是图上出现一条 275 米、笔直插进林子中央然后断掉的路（用户说的「道路生成的这么碎」）。
    * 真实聚落里不存在"修到半路停下"的路：路要么通到人家/田头/另一条路，要么根本没人修。</p>
    *
    * <p><b>判据</b>：一个路面点算「有人用」= 25 米内有房子/设施，或 14 米内有田，
    * 或 2.2 米内接着别的路。取最长的连续可用段；整条路没有一个可用点就整条删掉。
    * 每一步都过连通性守卫：删/剪之后分量变多就回滚（宁可留一段多余的尾巴，不能让路网散架）。</p>
    *
    * @param net 路网（就地修改）
    */
   private void trimUnservedTails(List<RoadSpec> net) {
      int trimmed = 0;
      int dropped = 0;
      for(int idx = net.size() - 1; idx >= 0; --idx) {
         RoadSpec r = net.get(idx);
         List<double[]> path = r.pathMeters();
         if (path == null || path.size() < 3 || "pier".equals(r.kind())
                 || "connector".equals(r.kind()) || r.bridgeCells() > 0) {
            continue;
         }
         String code = r.code() == null ? "" : r.code();
         if (isLinkRoad(code)) {
            continue;
         }
         boolean[] served = new boolean[path.size()];
         int head = -1;
         int tail = -1;
         for(int i = 0; i < path.size(); ++i) {
            double[] pt = path.get(i);
            served[i] = this.hasBuildingNear(pt[0], pt[1], 25.0)
                    || this.hasFieldNear(pt[0], pt[1], 14.0)
                    || touchesOtherRoad(net, r, pt);
            if (served[i]) {
               if (head < 0) {
                  head = i;
               }
               tail = i;
            }
         }
         if (head < 0) {
            // 整条路没有一个点挨着房子/田/别的路：它不是"缺一段"，是根本没人修
            List<RoadSpec> cand = new ArrayList(net);
            cand.remove(idx);
            if (MapNetwork.groupCount(cand) <= MapNetwork.groupCount(net)) {
               net.remove(idx);
               ++dropped;
            } else {
               this.bump("剪尾回滚");
            }
            continue;
         }
         double total = pathLength(path);
         double keep = this.indexedLength(path, head, tail);
         if (head == 0 && tail == path.size() - 1 || keep > total - 10.0) {
            continue;   // 两头都在用，或尾巴不到 10 米（剪了看不出差别，还容易把 T 字口剪歪）
         }
         // 逐次减半地往回放，直到连通性不再被破坏：宁可少剪，不能让路网散架
         int h = head;
         int t = tail;
         while(true) {
            List<double[]> cut = new ArrayList(path.subList(h, t + 1));
            double keepLen = this.indexedLength(cut, 0, cut.size() - 1);
            boolean ok = keepLen >= 12.0
                    && MapNetwork.groupCount(this.withReplaced(net, idx, cut)) <= MapNetwork.groupCount(net);
            if (ok) {
               net.set(idx, new RoadSpec(r.code(), r.name(), r.kind(), r.grade(), r.width(),
                       r.surface(), cut, r.bridgeCells()));
               ++trimmed;
               break;
            }
            if (h <= 0 && t >= path.size() - 1) {
               this.bump("剪尾回滚");
               break;
            }
            h /= 2;
            t = path.size() - 1 - (path.size() - 1 - t) / 2;
         }
      }
      if (trimmed > 0) {
         this.diag.merge("剪无人用尾段", trimmed, Integer::sum);
      }
      if (dropped > 0) {
         this.diag.merge("删无人用路", dropped, Integer::sum);
      }
   }

   /** 用一个候选列表试探：把第 idx 条换成 {@code path}（{@code null}=删掉）。 */
   private List<RoadSpec> withReplaced(List<RoadSpec> net, int idx, List<double[]> path) {
      List<RoadSpec> out = new ArrayList(net);
      RoadSpec r = net.get(idx);
      if (path == null) {
         out.remove(idx);
      } else {
         out.set(idx, new RoadSpec(r.code(), r.name(), r.kind(), r.grade(), r.width(),
                 r.surface(), path, r.bridgeCells()));
      }
      return out;
   }

   /** 折线上 a..b 两点的累计长度（米）。 */
   private double indexedLength(List<double[]> path, int a, int b) {
      double sum = (double)0.0F;
      for(int i = Math.max(1, a + 1); i <= b && i < path.size(); ++i) {
         sum += Math.hypot(path.get(i)[0] - path.get(i - 1)[0],
                 path.get(i)[1] - path.get(i - 1)[1]);
      }
      return sum;
   }

   /**
    * 删掉「又长又空」的路：400 米以上、整条路上 30 米内一栋房子都没有、14 米内几乎没有田。
    *
    * <p><b>为什么</b>：这种路只可能来自 AI 拓扑里一条语义没错、尺度全错的边——
    * 实测 57 号图那条 {@code dock_steps}（"码头台阶"）被修成了 1016 米、绕岛南岸一整圈，
    * 两端各接一条街，中间一公里荒地。真实的村镇不会为一片没有人的海岸修一条一公里的路。
    * 判据用「房子」为主、田为辅：一条服务农田的长路是正常的（田头本来就在村外），
    * 但两侧连一栋房子都没有的长路基本是伪路。</p>
    *
    * @param net 路网（就地修改）
    */
   private void pruneEmptyLongRoads(List<RoadSpec> net) {
      List<RoadSpec> out = new ArrayList();
      int dropped = 0;
      for(RoadSpec r : net) {
         List<double[]> path = r.pathMeters();
         if (path == null || path.size() < 2 || "pier".equals(r.kind())
                 || "connector".equals(r.kind()) || r.bridgeCells() > 0) {
            out.add(r);
            continue;
         }
         String code = r.code() == null ? "" : r.code();
         if (isLinkRoad(code)) {
            out.add(r);
            continue;
         }
         double len = pathLength(path);
         if (len < 400.0) {
            out.add(r);
            continue;
         }
         int houses = 0;
         int fields = 0;
         for(int i = 0; i < path.size(); ++i) {
            double[] pt = path.get(i);
            if (this.hasBuildingNear(pt[0], pt[1], 30.0)) {
               ++houses;
            }
            if (this.hasFieldNear(pt[0], pt[1], 14.0)) {
               ++fields;
            }
         }
         if (houses == 0 && fields < path.size() / 4) {
            ++dropped;
            continue;
         }
         out.add(r);
      }

      if (dropped > 0) {
         net.clear();
         net.addAll(out);
         this.diag.merge("删荒野长路", dropped, Integer::sum);
      }
   }

   /**
    * 去掉折线上的「尖刺」：A→B→C 若几乎原路折回，这段在图上就是一根扎出来的刺。
    *
    * <p><b>这些刺是怎么来的</b>：{@code MapNetwork.connect} 的端点吸附是「把断头延伸到最近的那条路」，
    * 而收尾阶段会连着跑两三遍（田路整备、收尾连接、缝合）。后一遍看到的最近点是<b>另一个方向</b>上的路，
    * 于是又往路头前面插一个点——就成了 A(旧头) → B(接头) → C(≈旧头) 的来回折。
    * 实测里每条街巷（含目的地路）都可能挂上一两根几米到二十米的刺。</p>
    *
    * <p><b>为什么不能直接删中间那个点</b>：中间点往往正是「和别的路接上」的那个接头，
    * 删了就断网。所以按情况处理——接头点保留，丢掉它两侧里<b>更远</b>的那一个（那是被取代的旧路头）；
    * 不是接头的折点（纯地形噪声）才直接删。删完过连通性守卫，分量变多就整条路保留原样。</p>
    *
    * @param net 路网（就地修改）
    */
   private void despeckleRoads(List<RoadSpec> net) {
      int fixed = 0;
      for(int i = 0; i < net.size(); ++i) {
         RoadSpec r = net.get(i);
         List<double[]> p = r.pathMeters();
         if (p == null || p.size() < 3) {
            continue;
         }
         List<double[]> out = new ArrayList();
         out.add(p.get(0));
         boolean changed = false;
         boolean stop = false;
         for(int k = 1; k < p.size() - 1 && !stop; ++k) {
            double[] a = (double[])out.get(out.size() - 1);
            double[] b = (double[])p.get(k);
            double[] c = (double[])p.get(k + 1);
            double ab = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double bc = Math.hypot(c[0] - b[0], c[1] - b[1]);
            // 折回判据用「转角」而不是「弦长」：吸附加的点常常是 110°~180° 的急折，
            // 弦长比（ac 远小于 ab/bc）只抓得到 180° 原路返回的那一种。
            double cosTurn = ab > 1e-9 && bc > 1e-9
                    ? ((b[0] - a[0]) * (c[0] - b[0]) + (b[1] - a[1]) * (c[1] - b[1])) / (ab * bc)
                    : 1.0;
            boolean fold = ab > (double)2.0F && bc > (double)2.0F && cosTurn < -0.35;
            if (!fold) {
               out.add(b);
               continue;
            }
            boolean joint = this.distToOtherRoads(net, r, b[0], b[1]) <= 2.5;
            changed = true;
            if (!joint) {
               continue;   // 纯折点：丢掉 B
            }
            if (k + 2 >= p.size() - 1) {
               // 尾巴上的刺：以接头点收尾，丢掉旧的尾点
               out.add(b);
               stop = true;
            } else {
               // 路头处的刺：用接头点替换旧路头
               out.set(out.size() - 1, b);
            }
         }
         if (!stop) {
            out.add((double[])p.get(p.size() - 1));
         }
         if (!changed || out.size() < 2) {
            continue;
         }
         List<RoadSpec> cand = this.withReplaced(net, i, out);
         if (MapNetwork.groupCount(cand) > MapNetwork.groupCount(net)) {
            this.bump("去刺回滚");
            continue;
         }
         net.set(i, (RoadSpec)cand.get(i));
         ++fixed;
      }
      if (fixed > 0) {
         this.diag.merge("路去刺", fixed, Integer::sum);
      }
   }

   // ==================== 路网整备（V1.6：就近连接 / 每户进门 / 目的地吸附） ====================

   /**
    * 把一条路拉成直线，会不会把原本临街的房子「甩到荒野里」。
    *
    * <p><b>为什么要有这道守卫</b>：排房用的是<b>当时</b>的路网几何，而 {@code shortcutRoads}
    * 与后续的剪枝会重新摆路。一条弯道被拉直成两点直线之后，原本门朝弯道开的人家可能
    * 一夜之间离路 14 米——在图上就是一户人家孤零零地站在草地上（实测三张图各有 3~4 户）。
    * 拉直只是观感修正，不能因此破坏「每户都连着路」这条硬要求，所以冲突时拉直让位。</p>
    *
    * @param road     原路
    * @param straight 拉直后的折线
    * @return true=有房子会被甩开，这条不许拉直
    */
   private boolean strandsBuildings(RoadSpec road, List<double[]> straight) {
      List<double[]> path = road.pathMeters();
      if (path == null || straight == null) {
         return false;
      }
      for(PlacedFeature f : this.features) {
         if (!"building".equals(f.kind())) {
            continue;
         }
         double before = MapNetwork.nearestOnPath(path, f.doorX(), f.doorZ())[2];
         if (before > 12.0) {
            continue;   // 本来就不挨着这条街，拉不拉直与它无关
         }
         double after = MapNetwork.nearestOnPath(straight, f.doorX(), f.doorZ())[2];
         if (after > before + 2.5) {
            return true;
         }
      }

      return false;
   }

   /**
    * 剪掉「两头都挨着别的路、自己只有几米」的碎路。
    *
    * <p>这类路的来源是 {@link MapNetwork#connect} 的缝合：最后一遍把两个端点各自拉到邻路上，
    * 中间的残余段就只剩两三米。它不承担任何连接（去掉之后连通性不变），在图上却把路口
    * 描粗成一个小疙瘩——这正是用户说的「道路碎」里最小的那一类。</p>
    *
    * <p>守卫：删之前数一遍连通分量，变多就不删（说明它其实是唯一的连接段）。</p>
    *
    * @param net 路网（就地修改）
    */
   private void pruneTinyRoads(List<RoadSpec> net) {
      int dropped = 0;
      for(int i = net.size() - 1; i >= 0; --i) {
         RoadSpec r = (RoadSpec)net.get(i);
         List<double[]> path = r.pathMeters();
         if (path == null || path.size() < 2 || isSkeleton(r) || "pier".equals(r.kind())
                 || isLinkRoad(r.code()) || r.bridgeCells() > 0) {
            continue;
         }
         if (pathLength(path) >= 6.0) {
            continue;
         }
         List<RoadSpec> cand = new ArrayList(net);
         cand.remove(i);
         if (MapNetwork.groupCount(cand) <= MapNetwork.groupCount(net)) {
            net.remove(i);
            ++dropped;
         }
      }

      if (dropped > 0) {
         this.diag.merge("剪碎路", dropped, Integer::sum);
      }
   }

   /**
    * 剪掉「一头接路、另一头落在野地里、整条才十几米」的尾巴路。
    *
    * <p><b>为什么单列一条规则</b>：{@link #trimDanglingTails} 有「剪完至少留 12 米」的保底，
    * 于是「整条路只有 16 米」的尾巴它一格都剪不动——端点判据说这个端头不服务任何东西，
    * 可保底说剪了太短，两条规则互相抵消，最后这截十几米的路原样留在图上。
    * 这种路从街口往外伸一小段，端头离最近的物件还有近二十米，不服务门、不服务田、
    * 不到水边——这就是用户说的「无意义道路」。</p>
    *
    * <p><b>判据（同时满足才删）</b>：① 非骨架、非栈桥、非连接路；② 长度 ≤ 40 米；
    * ③ 只有一头挨着别的路（是尾巴，不是连接段）；④ 全线没有服务对象
    * （临街的门 14 米内 / 田 14 米内 / 契约设施 25 米内）；⑤ 悬空那头不在水边 8 米内
    * （取水口、码头前滩的路本来就在水边收尾）。删之前过连通性守卫。</p>
    *
    * @param net 路网（就地删除）
    */
   private void pruneUnservedStubs(List<RoadSpec> net) {
      int dropped = 0;
      for(int i = net.size() - 1; i >= 0; --i) {
         RoadSpec r = (RoadSpec)net.get(i);
         List<double[]> path = r.pathMeters();
         if (path == null || path.size() < 2 || isSkeleton(r) || "pier".equals(r.kind())
                 || "connector".equals(r.kind()) || r.bridgeCells() > 0) {
            continue;
         }
         String code = r.code() == null ? "" : r.code();
         if (isLinkRoad(code) || code.startsWith("door_") || code.startsWith("farm_head")) {
            continue;
         }
         if (pathLength(path) > 40.0) {
            continue;
         }
         double[] head = (double[])path.get(0);
         double[] tail = (double[])path.get(path.size() - 1);
         boolean e0 = this.touchesOtherRoad(net, r, head);
         boolean e1 = this.touchesOtherRoad(net, r, tail);
         // 两头都接路 = 两段路之间的连接段；两头都不接 = 悬空碎片，另有规则处理
         if (e0 == e1) {
            continue;
         }
         double[] loose = e0 ? tail : head;
         // 只按「悬空那一头」判有没有服务对象：这条路的中段常常从某户门前 10 米处经过，
         // 但那户人家的门本来就临着正街（实测 3.4 米），并不是为这截尾巴开的门。
         // 按整条路判会把「从别人家门口路过」当成服务对象，尾巴就永远剪不掉。
         if (stubServes(loose)) {
            continue;
         }
         if (this.nearWater(loose[0], loose[1], 8.0)) {
            continue;
         }
         List<RoadSpec> cand = new ArrayList(net);
         cand.remove(i);
         if (MapNetwork.groupCount(cand) > MapNetwork.groupCount(net)) {
            this.bump("剪尾路回滚");
            continue;
         }
         net.remove(i);
         ++dropped;
      }

      if (dropped > 0) {
         this.diag.merge("剪无用尾路", dropped, Integer::sum);
      }
   }

   /** 这个端点附近有没有「值得为它修路」的东西：临街的门、田、契约设施。 */
   private boolean stubServes(double[] pt) {
      for(PlacedFeature f : this.features) {
         if ("building".equals(f.kind()) && !courtyardPart(f)) {
            boolean facility = f.semanticJson() != null
                    && f.semanticJson().contains("\"facility\":true");
            double reach = facility ? 25.0 : 14.0;
            if (Math.hypot(f.doorX() - pt[0], f.doorZ() - pt[1]) <= reach) {
               return true;
            }
         }
         if ("plaza".equals(f.kind()) && f.semanticJson() != null
                 && f.semanticJson().contains("\"facility\":true")
                 && Math.hypot(f.x() - pt[0], f.z() - pt[1]) <= 25.0) {
            return true;
         }
      }
      return this.fieldEdgeNear(pt[0], pt[1], 14.0) || this.nearHarbor(pt[0], pt[1], 16.0);
   }

   /** 给定点 {@code range} 米内有没有水面（水陆唯一入口仍走 {@code TerrainData.waterCell()}）。 */
   private boolean nearWater(double x, double z, double range) {
      int steps = Math.max(2, (int)Math.ceil(range));
      for(int dz = -steps; dz <= steps; ++dz) {
         for(int dx = -steps; dx <= steps; ++dx) {
            if ((double)(dx * dx + dz * dz) > range * range) {
               continue;
            }
            int ci = this.cellIndex(x + (double)dx, z + (double)dz);
            if (ci >= 0 && this.t.waterCell(ci)) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * <b>目的地吸附</b>：把「冲着某个设施修的路」的端点，重接到那座设施的门前。
    *
    * <p><b>为什么必须有这一步</b>：S3 的路是冲着一个<b>语义点</b>修的（雾隐林、灯塔、盐作坊），
    * 而这座设施真正落在哪儿，是 S4 的 {@code placeAnchoredFacilities} 在锚点附近"找一块
    * 放得下的地"决定的——两者常常差二三十米。旧管线的处理是<b>事后剪枝</b>
    * （{@code trimUnservedTails} 把尾巴剪到最后一个有服务对象的点），于是图上就出现
    * 「一条路插到林子中间就断了」或者「路从灯塔旁边 20 米处掠过、谁也不连谁」。
    * 正确的做法是<b>先落位、再把路接过去</b>，让路以门前收尾。</p>
    *
    * <p>只动「镇外的目的地端」：靠近聚落中心的那一端是 T 字口，属于骨架，不许动。
    * 田埂/下地土路（0 级）也不参与——田间小路不通设施。</p>
    *
    * @param net   路网（就地替换折线）
    * @param coreX 聚落中心 X（米）
    * @param coreZ 聚落中心 Z（米）
    */
   private void snapRoadEndsToFacilities(List<RoadSpec> net, double coreX, double coreZ) {
      int snapped = 0;

      for(int i = 0; i < net.size(); ++i) {
         RoadSpec r = (RoadSpec)net.get(i);
         List<double[]> path = r.pathMeters();
         if (path == null || path.size() < 2 || isSkeleton(r) || "pier".equals(r.kind())
                 || "connector".equals(r.kind()) || r.grade() < RoadGrade.VILLAGE) {
            continue;
         }
         for(int e = 0; e < 2; ++e) {
            double[] tip = e == 0 ? (double[])path.get(0) : (double[])path.get(path.size() - 1);
            if (Math.hypot(tip[0] - coreX, tip[1] - coreZ) < this.townRadius * 0.85) {
               continue;   // 镇内那一端是接骨架的 T 字口
            }
            PlacedFeature fac = this.nearestFacility(tip[0], tip[1], 55.0);
            if (fac == null) {
               continue;
            }
            double[] target = this.frontOfFacility(fac, tip[0], tip[1]);
            if (target == null || Math.hypot(target[0] - tip[0], target[1] - tip[1]) < 2.5) {
               continue;
            }
            if (!this.clearOfBuildings(tip[0], tip[1], target[0], target[1], fac.code(), false)) {
               continue;
            }
            List<double[]> np = new ArrayList(path);
            if (e == 0) {
               np.add(0, target);
            } else {
               np.add(target);
            }
            net.set(i, new RoadSpec(r.code(), r.name(), r.kind(), r.grade(), r.width(),
                    r.surface(), np, r.bridgeCells()));
            // 新接出来的这一小段也要压进占用场：后面排房时不能盖在它上面
            this.stampCapsule(tip, target, Math.max((double)1.0F, r.width() / (double)2.0F + 0.4), 1.0F);
            ++snapped;
            break;
         }
      }

      if (snapped > 0) {
         this.diag.merge("目的地吸附", snapped, Integer::sum);
      }
   }

   /** 离某点最近的已落位设施（契约设施，semanticJson 里带 facility:true）。 */
   private PlacedFeature nearestFacility(double x, double z, double maxDist) {
      double best = Double.MAX_VALUE;
      PlacedFeature hit = null;
      for(PlacedFeature f : this.features) {
         String sem = f.semanticJson();
         if (sem == null || !sem.contains("\"facility\":true")) {
            continue;
         }
         double d = Math.hypot(f.x() - x, f.z() - z);
         if (d < best) {
            best = d;
            hit = f;
         }
      }

      return best <= maxDist ? hit : null;
   }

   /**
    * 设施「门前」的位置：从设施中心朝来路方向，走到外接矩形边界，再往外推 2 米。
    *
    * <p>为什么用外接矩形而不是门坐标：{@code placeInRing} 给设施定的 doorDir 是"朝聚落中心"，
    * 而路可能从任何一侧过来。门前点必须落在<b>路来的那一侧</b>，否则路要绕着设施走半圈。</p>
    *
    * @param f     设施
    * @param fromX 来路方向上的参考点 X（米）
    * @param fromZ 来路方向上的参考点 Z（米）
    * @return 门前点；参考点与设施重合时返回 null
    */
   private double[] frontOfFacility(PlacedFeature f, double fromX, double fromZ) {
      double dx = fromX - f.x();
      double dz = fromZ - f.z();
      double len = Math.hypot(dx, dz);
      if (len < 0.5) {
         return null;
      }
      dx /= len;
      dz /= len;
      double rad = Math.toRadians(f.rot());
      double cos = Math.cos(rad);
      double sin = Math.sin(rad);
      double lx = dx * cos + dz * sin;
      double lz = -dx * sin + dz * cos;
      double hw = f.w() / (double)2.0F;
      double hd = f.d() / (double)2.0F;
      double t = Double.MAX_VALUE;
      if (Math.abs(lx) > 1.0E-6) {
         t = Math.min(t, hw / Math.abs(lx));
      }
      if (Math.abs(lz) > 1.0E-6) {
         t = Math.min(t, hd / Math.abs(lz));
      }
      if (t == Double.MAX_VALUE) {
         return null;
      }
      double push = t + 2.0;
      return new double[]{f.x() + dx * push, f.z() + dz * push};
   }

   /**
    * 两个点之间能不能拉一条线（不穿任何别人的房子、不下水）。
    *
    * @param x1         起点 X
    * @param z1         起点 Z
    * @param x2         终点 X
    * @param z2         终点 Z
    * @param ignoreCode 允许穿的那一栋（起点常压在自家墙上）
    * @param sameYard   是否连自家院墙一起忽略（院门前的路要穿过院墙缺口，不能算挡路）
    * @return true=干净
    */
   private boolean clearOfBuildings(double x1, double z1, double x2, double z2, String ignoreCode,
                                    boolean sameYard) {
      double len = Math.hypot(x2 - x1, z2 - z1);
      if (len < 0.5) {
         return true;
      }
      int steps = Math.max(2, (int)Math.ceil(len / 0.5));

      for(int s = 1; s < steps; ++s) {
         double t = (double)s / (double)steps;
         double x = x1 + (x2 - x1) * t;
         double z = z1 + (z2 - z1) * t;
         int ci = this.cellIndex(x, z);
         if (ci < 0 || this.t.waterCell(ci)) {
            return false;
         }

         for(PlacedFeature b : this.features) {
            if (!"building".equals(b.kind()) && !"wall".equals(b.kind())) {
               continue;
            }
            if (ignoreCode != null && ignoreCode.equals(b.code())) {
               continue;
            }
            String sem = b.semanticJson();
            // 只放行「院墙 / 院门」这两类可穿越物：旧条件按 compound 语义 + 14 米半径整体放行，
            // 结果把自家厢房也一起放行了——连接路于是直接从厢房的屋身上穿过去。
            if (sameYard && ("wall".equals(b.kind()) || (sem != null && sem.contains("\"part\":2")))) {
               continue;   // 院墙/院门：门前的路本来就打这儿过
            }
            // 净空 1.6 米：路缘贴墙走会压在出檐上
            if (pointInPoly(x, z, this.rect(b.x(), b.z(), b.w() + 1.6, b.d() + 1.6, b.rot()))) {
               return false;
            }
         }
      }

      return true;
   }

   /**
    * 宅院的内部房不单独接路——纳入「院内」的三种情况：
    * <ol>
    *   <li>后厢房 / 柴房（{@code "rear":true} / {@code "annex":true}）：门开在自家院子里；</li>
    *   <li>三合院的正屋与东西厢房（{@code "compound":true} 且 {@code part != 2}）：
    *       整个院落只有<b>院门</b>朝街，正屋与厢房都是从院子里进。</li>
    * </ol>
    *
    * <p>这是四合院/两进院的真实动线，不是"漏了一条路"。所以「每户都接路」的判据里要把它们
    * 排除，只看<b>院门</b>（{@code part==2}）——否则会为了一个本来就不朝街的门，
    * 硬在院子里画一条穿过正房的小径。实测三张图里"离路 14 米的房子"多数就是这一类。</p>
    *
    * @param f 要素
    * @return true=属于宅院内部（不单独接路）
    */
   private static boolean courtyardPart(PlacedFeature f) {
      String s = f.semanticJson();
      if (s == null) {
         return false;
      }
      if (s.contains("\"rear\":true") || s.contains("\"annex\":true")) {
         return true;
      }
      return s.contains("\"compound\":true") && !s.contains("\"part\":2");
   }

   /**
    * 统计「有几户人家没接路 / 最远的那户离路多少米」。
    *
    * <p>为什么要单独抽出来：这个数要量两次——<b>排房刚结束时</b>量一次，
    * <b>整备收尾时</b>再量一次。两个数一对比就能定位到"是排房时就没接上"，
    * 还是"排完房之后路被拉直/剪尾挪开了"。前者是落位口径问题，后者是整备顺序问题。</p>
    *
    * @param net    路网
    * @param suffix 诊断键后缀（{@code null} 表示最终口径）
    */
   private void auditDoors(List<RoadSpec> net, String suffix) {
      if (net == null || net.isEmpty()) {
         return;
      }
      int unserved = 0;
      double worst = (double)0.0F;

      for(PlacedFeature f : this.features) {
         if ("building".equals(f.kind())) {
            if (!courtyardPart(f)) {
               double d = this.doorRoadDistance(net, f);
               if (d > 7.0) {
                  ++unserved;
               }
               worst = Math.max(worst, Math.min(d, (double)999.0F));
            }
         }
      }

      String tail = suffix == null ? "" : "@" + suffix;
      this.diag.put("未接路房" + tail, unserved);
      this.diag.put("房到路最远" + tail, (int)Math.round(worst));
   }

   /** 某个点到路网的最近距离（米）。 */
   private double doorRoadDistance(List<RoadSpec> net, PlacedFeature f) {
      double[] q = this.nearestRoadPoint(net, f.doorX(), f.doorZ());
      return q == null ? Double.MAX_VALUE : q[2];
   }

   /**
    * <b>每户都进门</b>：门口离路超过 7 米的独立房屋，补一条入户小径，直接连到<b>最近的
    * 那个路面点</b>。
    *
    * <p><b>为什么必须留这一步</b>：排房时门口是贴着路的，但路在之后还会被拉直、被剪尾、
    * 被缝合——每一次都可能把路挪开几米。实测三张图各有 3~6 户人家门离路 13~18 米，
    * 在航拍上就是「一户人家孤零零站在草地上，跟村子不连」。真实聚落里不存在这种房子：
    * 再偏的人家也有一条踩出来的小路通到村道上。</p>
    *
    * <p>为什么是「最近的路面点」而不是「最近的路头」：路是中段最靠近房子，
    * 接在路头就等于绕远——这正是用户点名的「明明有更近的地方，偏要在最远的地方接」。</p>
    *
    * @param net 路网（就地追加）
    */
   private void connectDwellingsToRoads(List<RoadSpec> net) {
      if (net == null || net.isEmpty()) {
         return;
      }
      int added = 0;
      int unserved = 0;
      double worst = (double)0.0F;

      for(PlacedFeature f : this.features) {
         if (!"building".equals(f.kind())) {
            continue;
         }
         if (courtyardPart(f)) {
            continue;
         }
         double d = this.doorRoadDistance(net, f);
         if (d <= 7.0) {
            worst = Math.max(worst, d);
            continue;
         }
         String sem = f.semanticJson();
         boolean gate = sem != null && sem.contains("\"compound\":true");
         // 候选接头：每条路各给一个"最近点"，按距离从近到远试，直到找到一条不穿别人房子的。
         // 只试"全局最近的那一个点"是不够的——被夹在两排房子中间的那户人家，
         // 最近的点往往正好在邻居的屋身上。
         List<double[]> cands = new ArrayList();
         for(RoadSpec r : net) {
            if (r.pathMeters() != null && r.pathMeters().size() >= 2) {
               double[] q = MapNetwork.nearestOnPath(r.pathMeters(), f.doorX(), f.doorZ());
               if (q[2] <= (double)30.0F) {
                  cands.add(q);
               }
            }
         }
         cands.sort((x, y) -> Double.compare(x[2], y[2]));
         // 门朝外：入户小径必须从门口「朝门前方向」离开，不能从后墙穿屋子出去。
         // 门开在一侧山墙、而最近的路却在屋子另一侧时，两点直连的小径会横穿自己的屋身
         // （图上就是房子压在路面上），所以先按门的外法线筛掉"要穿屋"的候选。
         double[] outward = new double[]{f.doorX() - f.x(), f.doorZ() - f.z()};
         double olen = Math.hypot(outward[0], outward[1]);
         if (olen < (double)0.1F) {
            outward = new double[]{Math.cos(Math.toRadians(f.doorDir())), Math.sin(Math.toRadians(f.doorDir()))};
            olen = 1.0;
         }
         double ux = outward[0] / olen;
         double uz = outward[1] / olen;
         // 小径从「门阶外 1.4 米」起笔：路面半宽 0.8 米，若从门槛点铺起，
         // 这 0.8 米会直接压进自己的墙里（房子就算"压在路面上"）。
         double startX = f.doorX() + ux * 1.4;
         double[] q = null;
         for(int k = 0; k < cands.size() && k < 6; ++k) {
            double[] c = cands.get(k);
            double dot = (c[0] - f.doorX()) * outward[0] + (c[1] - f.doorZ()) * outward[1];
            if (dot <= (double)0.0F) {
               continue;   // 这一头要穿过自家屋子才能到，换下一个候选
            }
            if (Math.hypot(c[0] - f.doorX(), c[1] - f.doorZ()) < (double)2.0F) {
               continue;   // 路就在门槛下，不用再铺一条（铺了也是压墙）
            }
            if (this.clearOfBuildings(f.doorX(), f.doorZ(), c[0], c[1], f.code(), gate)
                    && !this.segmentCrossesPaved(f.doorX(), f.doorZ(), c[0], c[1])) {
               q = c;
               break;
            }
         }
         if (q == null) {
            ++unserved;
            worst = Math.max(worst, d);
            continue;
         }
         double startZ = f.doorZ() + uz * 1.4;
         List<double[]> path = new ArrayList();
         path.add(new double[]{startX, startZ});
         path.add(new double[]{q[0], q[1]});
         // 宽度下限 1.6 是硬约束（MapGenPipelineOfflineTest 断言 1.6~8.0）：小径也是路，
         // 太窄会在渲染时被路肩盖掉，闸门会把整张图判成「路网无效」。
         net.add(new RoadSpec(this.nextCode("door"), "入户小径", "path", RoadGrade.TRACK,
                 (double)1.6F, "dirt", path, 0));
         ++added;
      }

      if (added > 0) {
         this.diag.merge("入户小径", added, Integer::sum);
      }
      this.auditDoors(net, (String)null);
      if (unserved > 0) {
         this.diag.merge("未接路房:修不了", unserved, Integer::sum);
      }
   }

   /**
    * 剪掉「伸进野地就断掉」的路尾：路的<b>末端</b>必须能领到东西（20 米内有房、15 米内有田、
    * 或者接着别的路），否则一路退到最近一个"领得到东西"的点收尾。
    *
    * <p><b>和 {@link #trimUnservedTails} 的区别</b>：那一步按 25 米判"沿途有没有人用"，
    * 只要路中段有房子就整条保留——于是「最后一户之后还往前伸三十米」这种尾巴留下来了。
    * 用户看到的就是一条路的尽头插在草地/林子里，什么也不通。这里只看<b>端头本身</b>，
    * 用更紧的口径（20 米房 / 15 米田）把这种尾巴收回去。</p>
    *
    * <p>守卫：每次剪完都数连通分量，变多就整条路保留原样；且至少留 12 米，
    * 免得把一条路剪成一个点。</p>
    *
    * @param net 路网（就地修改）
    */
   private void trimDanglingTails(List<RoadSpec> net) {
      int trimmed = 0;

      for(int idx = net.size() - 1; idx >= 0; --idx) {
         RoadSpec r = (RoadSpec)net.get(idx);
         List<double[]> path = r.pathMeters();
         if (path == null || path.size() < 2 || "pier".equals(r.kind())
                 || "connector".equals(r.kind()) || r.bridgeCells() > 0) {
            continue;
         }
         // 骨架街（正街/后街/横巷）也要能剪野尾：实测一张图上正街末端伸进野地 34 米——
         // 既不到人家也不到田，俯视图上就是"一条街修到草里就没了"。但骨架是镇子的骨架，
         // 所以对它们改用更保守的下限（至少留 24 米），而不是整类豁免。
         boolean skeleton = isSkeleton(r);
         String code = r.code() == null ? "" : r.code();
         // 连接路/入户小径的末端天生就是"接着上一个东西"，不参与；田头路要留到田角
         if (isLinkRoad(code) || code.startsWith("door_") || code.startsWith("farm_head")) {
            continue;
         }
         List<double[]> p = new ArrayList(path);
         // 两点直段也要能剪：按目的地契约生成的路往往就是一条两点直线，目的地没能落地时
         // 它会一路扎进野地。原来这里要求 `path.size() >= 3`，这种尾巴直接跳过、永远剪不掉。
         // 先按 ≤6 米加密成折点（几何形状不变），下面的逐点回退逻辑就同样适用。
         if (p.size() == 2) {
            p = this.densify(p, (double)6.0F);
         }
         boolean changed = false;

         for(int end = 0; end < 2; ++end) {
            while (p.size() >= 3) {
               double[] tip = end == 0 ? (double[])p.get(0) : (double[])p.get(p.size() - 1);
               if (this.endServes(net, r, tip)) {
                  break;
               }
               double[] next = end == 0 ? (double[])p.get(1) : (double[])p.get(p.size() - 2);
               double keepMin = skeleton ? (double)24.0F : (double)12.0F;
               if (pathLength(p) - Math.hypot(tip[0] - next[0], tip[1] - next[1]) < keepMin) {
                  break;
               }
               if (end == 0) {
                  p.remove(0);
               } else {
                  p.remove(p.size() - 1);
               }
               changed = true;
            }
         }

         if (changed && p.size() >= 2) {
            if (MapNetwork.groupCount(this.withReplaced(net, idx, p)) > MapNetwork.groupCount(net)) {
               this.bump("剪荒尾回滚");
            } else {
               net.set(idx, new RoadSpec(r.code(), r.name(), r.kind(), r.grade(), r.width(),
                       r.surface(), p, r.bridgeCells()));
               ++trimmed;
            }
         }
      }

      if (trimmed > 0) {
         this.diag.merge("剪荒野尾", trimmed, Integer::sum);
      }
   }

   /**
    * 这个端点「领得到东西」吗：<b>临街的门</b> 18 米内、田 15 米内、或者接着别的路。
    *
    * <p>为什么不用 {@code hasBuildingNear}：那会把<b>院内的正屋</b>也算成服务对象——
    * 一座三合院的正屋离路 15 米很正常（它在院子里），可要是拿它当判据，一条已经
    * 越过院门 20 米的野地尾巴就会被判成"有用"而留下来。端头该看的是「还够得着谁家的门」。</p>
    *
    * @param net 路网（判断是否接着别的路）
    * @param self 自己（不算）
    * @param pt   端点
    * @return true=这个端头有用
    */
   /**
    * 按给定间距加密折线（只在原有直段上补采样点，几何形状不变，首尾点保持原样）。
    *
    * @param path 原折线（米）
    * @param step 目标间距（米）
    * @return 加密后的折线
    */
   private List<double[]> densify(List<double[]> path, double step) {
      List<double[]> out = new ArrayList();
      if (path == null || path.size() < 2) {
         return out;
      }
      out.add(path.get(0));
      for(int i = 1; i < path.size(); ++i) {
         double[] a = path.get(i - 1);
         double[] b = path.get(i);
         double d = Math.hypot(b[0] - a[0], b[1] - a[1]);
         int n = Math.max(1, (int)Math.ceil(d / Math.max((double)0.5F, step)));
         for(int k = 1; k <= n; ++k) {
            double t = (double)k / (double)n;
            out.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
         }
      }
      return out;
   }

   private boolean endServes(List<RoadSpec> net, RoadSpec self, double[] pt) {
      for(PlacedFeature f : this.features) {
         if ("building".equals(f.kind()) && !courtyardPart(f)
                 && Math.hypot(f.doorX() - pt[0], f.doorZ() - pt[1]) <= (double)14.0F) {
            return true;
         }
      }
      // 田按「到田块的边」判，不按「到田块中心」判：正方形田块的中心距含半个对角，
      // 用中心距会把一段离田边还有十几米的路尾判成"领到田了"，尾巴就剪不掉。
      return this.fieldEdgeNear(pt[0], pt[1], (double)15.0F)
              || this.nearHarbor(pt[0], pt[1], (double)12.0F)
              || touchesOtherRoad(net, self, pt);
   }

   /**
    * 这个点是不是在码头口（栈桥根 / 货场）附近。
    *
    * <p>栈桥与货场都不是"建筑"，所以按 {@link #endServes} 的常规口径（人家门口、田边、接别的路）
    * 判，码头口会被当成"什么也没有的野地"——通往码头的路就会被当成无意义的尾巴剪掉。
    * 这里把码头当成和"人家门口"等价的服务目标。</p>
    *
    * @param x     点 X（米）
    * @param z     点 Z（米）
    * @param range 允许的距离（米）
    * @return true=够得着码头
    */
   private boolean nearHarbor(double x, double z, double range) {
      for(PlacedFeature f : this.features) {
         if ("pier".equals(f.kind()) || "quay".equals(f.kind())) {
            if (Math.hypot(f.doorX() - x, f.doorZ() - z) <= range
                    || Math.hypot(f.x() - x, f.z() - z) <= range + Math.max(f.w(), f.d()) * 0.5) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * 剪掉路两端的「钩子」：<b>拐个直角然后十来米就没了</b>。
    *
    * <p><b>这种钩子是怎么长出来的</b>：{@link StreetNetwork#walk} 撞到陡坡/水面时会依次试更大的
    * 偏转角绕过去，绕过去之后往往马上又走不动了——于是路的末尾留下「先拐 90°，再走十米」的一小段。
    * 航拍上就是街道尽头突然伸出一根小钩子，通向草地。AI 的目的地路（A* 到不了目标附近时）
    * 也会留下同样的形状。</p>
    *
    * <p><b>为什么不去动骨架的"拐弯"</b>：真实街道拐直角是正常的（十字路口、丁字口）。
    * 所以要同时满足四条才剪：① 末段短（&lt;20 米）；② 转角急（&gt;70°）；
    * ③ 端头<b>够不着任何一家的门</b>（不是"最后一户的门前路"）；④ 端头不接别的路（不是路口）。
    * 这四条同时成立，它就不可能是正常的路口或门前路。</p>
    *
    * @param net 路网（就地修改）
    */
   private void trimEndHooks(List<RoadSpec> net) {
      int trimmed = 0;

      for(int idx = 0; idx < net.size(); ++idx) {
         RoadSpec r = (RoadSpec)net.get(idx);
         List<double[]> src = r.pathMeters();
         if (src == null || src.size() < 3 || "pier".equals(r.kind())) {
            continue;
         }
         List<double[]> p = new ArrayList(src);
         boolean changed = false;

         for(int end = 0; end < 2; ++end) {
            for(int guard = 0; guard < 2 && p.size() >= 3; ++guard) {
               double[] tip = end == 0 ? (double[])p.get(0) : (double[])p.get(p.size() - 1);
               double[] mid = end == 0 ? (double[])p.get(1) : (double[])p.get(p.size() - 2);
               double[] prev = end == 0 ? (double[])p.get(2) : (double[])p.get(p.size() - 3);
               double tail = Math.hypot(mid[0] - tip[0], mid[1] - tip[1]);
               double head = Math.hypot(prev[0] - mid[0], prev[1] - mid[1]);
               if (tail > (double)20.0F || head < 1.0E-6 || tail < 1.0E-6) {
                  break;
               }
               double cosTurn = ((mid[0] - tip[0]) * (prev[0] - mid[0])
                       + (mid[1] - tip[1]) * (prev[1] - mid[1])) / (tail * head);
               if (cosTurn > 0.50) {
                  break;   // 转角不到 60°：这是正常弯道
               }
               if (touchesOtherRoad(net, r, tip)) {
                  break;   // 端头接着别的路：这是路口，不能剪
               }
               if (this.endServes(net, r, tip)) {
                  break;   // 钩子尽头就是某户人家/某片田，那它是门前路
               }
               // 至少留 8 米、且不短于原长的 55%：钩子多在短支线上，按死数 25 米卡会把它们全放过
               double remain = pathLength(p) - tail;
               if (remain < (double)8.0F || remain < pathLength(src) * 0.55) {
                  break;
               }
               if (end == 0) {
                  p.remove(0);
               } else {
                  p.remove(p.size() - 1);
               }
               changed = true;
            }
         }

         if (changed && p.size() >= 2) {
            net.set(idx, new RoadSpec(r.code(), r.name(), r.kind(), r.grade(), r.width(),
                    r.surface(), p, r.bridgeCells()));
            ++trimmed;
         }
      }

      if (trimmed > 0) {
         this.diag.merge("剪路头钩", trimmed, Integer::sum);
      }
   }

   /**
    * <b>给每个契约设施修一条进道</b>：设施离路太远（&gt;10 米）就按地形补一条路过去。
    *
    * <p><b>为什么不能只靠"把路端点挪过来"</b>：灯塔在海岬高点上、磨坊在河湾边，
    * 常常离最近的路一百多米——没有任何一条路的端点在那附近，挪也无处可挪。实测 777 号图里
    * 「灯塔」离最近的路 105 米，孤零零立在海岸上，图上一根线都没通到它。
    * 真实聚落里，凡是有人的建筑，门前必有一条路；没人去的地方不会有房子。
    * 所以这里有路就用最近点接、没路就在地形上<b>修进去</b>（A* 会自己绕开陡坡/水面/房子）。</p>
    *
    * <p>尺度守卫：超过 {@code MAX_FACILITY_ROAD} 米就不修了——那不是"进道"，是另一条村道，
    * 应该出现在 AI 的拓扑里，而不是落位器偷偷补一条一公里的路。</p>
    *
    * @param net 路网（就地追加）
    */
   private void connectFacilities(List<RoadSpec> net) {
      if (net == null || net.isEmpty()) {
         return;
      }
      int added = 0;
      double mpc = Math.max(0.05, this.mpc);
      float[] cost = this.costWithClearance();

      for(PlacedFeature fac : new ArrayList<PlacedFeature>(this.features)) {
         String sem = fac.semanticJson();
         if (sem == null || !sem.contains("\"facility\":true") || "plaza".equals(fac.kind())) {
            continue;
         }
         double[] hit = this.nearestRoadPoint(net, fac.x(), fac.z());
         if (hit == null || hit[2] <= (double)10.0F || hit[2] > MAX_FACILITY_ROAD) {
            continue;
         }
         double[] target = this.frontOfFacility(fac, hit[0], hit[1]);
         if (target == null) {
            continue;
         }
         List<double[]> path;
         RoadRouter.Route r = RoadRouter.route(this.t, cost,
                 (int)Math.round(hit[0] / mpc), (int)Math.round(hit[1] / mpc),
                 (int)Math.round(target[0] / mpc), (int)Math.round(target[1] / mpc), (double)0.6F);
         if (r != null && r.path().size() >= 2) {
            path = new ArrayList();
            for(double[] pt : r.path()) {
               path.add(new double[]{pt[0] * mpc, pt[1] * mpc});
            }
         } else if (this.clearOfBuildings(hit[0], hit[1], target[0], target[1], fac.code(), false)) {
            path = new ArrayList();
            path.add(new double[]{hit[0], hit[1]});
            path.add(new double[]{target[0], target[1]});
         } else {
            this.bump("设施进道:修不通");
            continue;
         }
         // 端点对齐到真实的路面点/门前点，保证"接着"而不是"差几米"
         path.set(0, new double[]{hit[0], hit[1]});
         path.set(path.size() - 1, new double[]{target[0], target[1]});
         List<double[]> thin = RoadRouter.simplify(path, (double)1.2F);
         if (thin.size() < 2) {
            continue;
         }
         // 长进道降一档：通往灯塔/瞭望台的那种三百多米的沿崖小道，现实中就是一条窄土路
         // （走不了车），不能画成和村道一样的 3 米宽。短进道（设施就在村口）保持村道级。
         double roadLen = MapNetwork.length(thin);
         boolean far = roadLen > (double)260.0F;
         net.add(new RoadSpec(this.nextCode("acc"), far ? "沿崖小道" : "进道", "path",
                 far ? RoadGrade.TRACK : RoadGrade.VILLAGE, far ? (double)2.0F : (double)3.0F,
                 "dirt", thin, r == null ? 0 : Math.min(r.bridgeCells(), 18)));
         for(int k = 1; k < thin.size(); ++k) {
            this.stampCapsule(thin.get(k - 1), thin.get(k), (double)1.9F, 1.0F);
         }
         ++added;
      }

      if (added > 0) {
         this.diag.merge("设施进道", added, Integer::sum);
      }
   }

   /**
    * 设施进道的长度上限（米）。
    *
    * <p>给到 900：海岬上的灯塔、河湾边的磨坊离村子三百多米是常态，这条"沿崖小道"
    * 是真实存在的（有人住的地方必有人走的路）。真正的护栏不是长度，而是
    * ① 只有<b>契约设施</b>才配得上这条路；② 超长的一律降成 0 级窄土路（见
    * {@link #connectFacilities}），图上一眼能看出它和村道不是一回事。</p>
    */
   private static final double MAX_FACILITY_ROAD = 900.0;

   /**
    * 两点之间的一条「不穿房」的连接路径（米）；穿房就绕，绕不出来返回 {@code null}。
    *
    * <p>田间连接路是从村道直接连到田头线的<b>直段</b>（真实农田里就是垂直路面的短坡道），
    * 但田间要是先落了一排房子，这条直段就会从人家屋里穿过去——房子压在路面上，
    * 正是 {@code noBuildingStandsOnPavement} 这一类硬约束要拦的图。</p>
    *
    * <p>所以：直段先做建筑净空检查，不过就按 {@link #costWithClearance()} 绕行（绕行代价里
    * 建筑占位是重罚），绕不出来这一段路就<b>不修</b>——宁可田头没有路，也不能把房子压了。</p>
    */
   private List<double[]> linkClearOfBuildings(double x1, double z1, double x2, double z2, String ignoreCode) {
      if (this.clearOfBuildings(x1, z1, x2, z2, ignoreCode, true)) {
         List<double[]> path = new ArrayList();
         path.add(new double[]{x1, z1});
         path.add(new double[]{x2, z2});
         return path;
      }
      double mpc = Math.max(0.05, this.mpc);
      RoadRouter.Route r = RoadRouter.route(this.t, this.costWithClearance(),
              (int)Math.round(x1 / mpc), (int)Math.round(z1 / mpc),
              (int)Math.round(x2 / mpc), (int)Math.round(z2 / mpc), (double)0.8F);
      if (r == null || r.path().size() < 2) {
         return null;
      }
      List<double[]> path = new ArrayList();
      for(double[] pt : r.path()) {
         path.add(new double[]{pt[0] * mpc, pt[1] * mpc});
      }
      path.set(0, new double[]{x1, z1});
      path.set(path.size() - 1, new double[]{x2, z2});
      return path;
   }

   /** 附近有没有田（田块中心距 ≤ radius + 半对角线）。 */
   private boolean hasFieldNear(double x, double z, double radius) {
      for(PlacedFeature f : this.features) {
         if ("field".equals(f.kind())
                 && Math.hypot(f.x() - x, f.z() - z) <= radius + Math.max(f.w(), f.d()) * 0.71) {
            return true;
         }
      }
      return false;
   }

   /**
    * 这个点到最近的田块<b>边</b>是否在 {@code margin} 米以内。
    *
    * <p>田块是 N×N 的正方形，{@link #hasFieldNear} 用的是「中心距 + 半对角线」，
    * 于是中心距 20 米的点也会被判成"就在田边"。判断「一条路的端头是不是领到了田」
    * 必须按到边的距离算，否则离田边还有十几米的路尾会被判成有用，永远剪不掉。</p>
    *
    * @param x      点 X（米）
    * @param z      点 Z（米）
    * @param margin 允许的边距（米）
    * @return true=边距以内有田
    */
   private boolean fieldEdgeNear(double x, double z, double margin) {
      for(PlacedFeature f : this.features) {
         if ("field".equals(f.kind())
                 && Math.hypot(f.x() - x, f.z() - z) - Math.max(f.w(), f.d()) * 0.5 <= margin) {
            return true;
         }
      }
      return false;
   }

   private void pruneUnbuiltTails(List<RoadSpec> net) {
      int trimmed = 0;
      List<RoadSpec> out = new ArrayList();

      for(RoadSpec r : net) {
         boolean prunable = r.grade() <= 1 && !isLinkRoad(r.code())
                 && ("path".equals(r.kind()) || "trail".equals(r.kind()) || "lane".equals(r.kind())
                     || "alley".equals(r.kind()))
                 && r.pathMeters() != null && r.pathMeters().size() >= 4;
         if (!prunable) {
            out.add(r);
         } else {
            List<double[]> path = new ArrayList(r.pathMeters());
            boolean changed = false;

            for(int end = 0; end < 2; ++end) {
               for(; path.size() >= 4; changed = true) {
                  double[] tip = end == 0 ? (double[])path.get(0) : (double[])path.get(path.size() - 1);
                  // 码头口与人家门口同等对待：栈桥/货场不是建筑，只按 hasBuildingNear 判，
                  // 通往海边的那一截会被当成"没建物的断头"剪掉。
                  if (this.hasBuildingNear(tip[0], tip[1], (double)14.0F)
                          || this.nearHarbor(tip[0], tip[1], (double)14.0F)
                          || touchesOtherRoad(net, r, tip)) {
                     break;
                  }

                  double total = pathLength(path);
                  double[] next = end == 0 ? (double[])path.get(1) : (double[])path.get(path.size() - 2);
                  double seg = Math.hypot(tip[0] - next[0], tip[1] - next[1]);
                  if (total - seg < (double)30.0F) {
                     break;
                  }

                  if (end == 0) {
                     path.remove(0);
                  } else {
                     path.remove(path.size() - 1);
                  }
               }
            }

            if (changed && path.size() >= 2) {
               ++trimmed;
               out.add(new RoadSpec(r.code(), r.name(), r.kind(), r.grade(), r.width(), r.surface(), path, r.bridgeCells()));
            } else {
               out.add(r);
            }
         }
      }

      if (trimmed > 0) {
         net.clear();
         net.addAll(out);
         this.diag.merge("剪断头路", trimmed, Integer::sum);
      }

   }

   private static double pathLength(List<double[]> path) {
      double len = (double)0.0F;

      for(int i = 1; i < path.size(); ++i) {
         len += Math.hypot(((double[])path.get(i))[0] - ((double[])path.get(i - 1))[0], ((double[])path.get(i))[1] - ((double[])path.get(i - 1))[1]);
      }

      return len;
   }

   private boolean hasBuildingNear(double x, double z, double radius) {
      for(PlacedFeature f : this.features) {
         if ("building".equals(f.kind()) && !(Math.abs(f.x() - x) > radius + f.w()) && !(Math.abs(f.z() - z) > radius + f.d()) && Math.hypot(f.x() - x, f.z() - z) <= radius + Math.max(f.w(), f.d()) * (double)0.5F) {
            return true;
         }
      }

      return false;
   }

   private static boolean touchesOtherRoad(List<RoadSpec> net, RoadSpec self, double[] pt) {
      for(RoadSpec o : net) {
         // 同 code 的"另一份自己"不算别的路：去重之前的路网里确实会存在同名副本，
         // 一旦把副本当成"我接着别的路"，自己的野地尾巴就永远剪不掉。
         if (o != self && !Objects.equals(o.code(), self.code()) && o.pathMeters() != null) {
            for(double[] q : o.pathMeters()) {
               if (Math.hypot(q[0] - pt[0], q[1] - pt[1]) <= (double)2.5F) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   /**
    * 把「扎进水里」的路端收回岸上。
    *
    * <p>取水口、渡口、海滨小道的端头本来就该停在水边，但路径点常常多走两三米、落进水面格，
    * 渲染时就是一条路伸进河里消失（再往下还会催生一座"跨到河心"的假桥）。这里只做一件事：
    * 端头若是水面格，就一路退到最后一个陆地点。</p>
    *
    * <p>跳过栈桥（它本来就该在水上）和桥面路（过河段由桥承担）。</p>
    *
    * @param net 路网（就地替换元素）
    */
   private void trimTipsInWater(List<RoadSpec> net) {
      int cut = 0;
      for(int i = 0; i < net.size(); ++i) {
         RoadSpec r = (RoadSpec)net.get(i);
         List<double[]> path = r.pathMeters();
         if (path == null || path.size() < 3 || "pier".equals(r.kind()) || r.bridgeCells() > 0) {
            continue;
         }
         List<double[]> p = new ArrayList(path);
         boolean changed = false;
         for(int end = 0; end < 2; ++end) {
            while(p.size() >= 3) {
               double[] tip = end == 0 ? (double[])p.get(0) : (double[])p.get(p.size() - 1);
               int ci = this.cellIndex(tip[0], tip[1]);
               if (ci < 0 || !this.t.waterCell(ci)) {
                  break;
               }
               if (end == 0) {
                  p.remove(0);
               } else {
                  p.remove(p.size() - 1);
               }
               changed = true;
            }
         }
         if (changed && pathLength(p) >= (double)5.0F) {
            net.set(i, new RoadSpec(r.code(), r.name(), r.kind(), r.grade(), r.width(),
                    r.surface(), p, r.bridgeCells()));
            ++cut;
         }
      }
      if (cut > 0) {
         this.diag.merge("剪入水端头", cut, Integer::sum);
      }
   }

   private void placeBridges(List<RoadSpec> roads) {
      List<double[]> done = new ArrayList();

      for(RoadSpec r : roads) {
         int bridges = r.bridgeCells();
         if (bridges > 0) {
            int bestRun = 0;
            int bestFrom = -1;
            int bestTo = -1;
            int runCount = 0;
            int from = -1;
            List<double[]> path = r.pathMeters();
            // 判「哪一段在水上」必须按<b>几何重采样</b>，不能看折线顶点：
            // A* 的逐格路径经抽稀 + Chaikin 圆角之后只剩十来个顶点，一条 4 米宽的河
            // 最多落进一个顶点（往往一个都没有）。旧口径要求「连续 2 个顶点在水里」，
            // 于是桥永远判不出来——路上带着 bridge_over=water，图上却没有桥。
            List<double[]> samp = new ArrayList();
            double[] cum = arcLengths(path);
            double total = cum[cum.length - 1];
            int samples = Math.max(2, (int)Math.ceil(total / 0.5));
            for(int i = 0; i <= samples; ++i) {
               samp.add(pointAt(path, cum, Math.min(total, (double)i * 0.5)));
            }

            for(int i = 0; i < samp.size(); ++i) {
               int ci = this.cellIndex(((double[])samp.get(i))[0], ((double[])samp.get(i))[1]);
               boolean wet = ci >= 0 && this.t.waterCell(ci);
               if (wet) {
                  if (runCount == 0) {
                     from = i;
                  }

                  ++runCount;
                  if (runCount > bestRun) {
                     bestRun = runCount;
                     bestFrom = from;
                     bestTo = i;
                  }
               } else {
                  runCount = 0;
               }
            }

            // 过河段必须是<b>路中间</b>的一段：只有一个岸上有路、另一边是水的"桥"是死路——
            // 图上就是一座桥跨到河心/对岸沙地上就断了，什么也不通。
            // （典型来源：取水的巷子一路走到河面里，road tip 落在水面格上。）
            // 过水段必须夹在路中间，且至少 2 米宽（0.5 米采样 → 4 个样点）：
            // 只有一头在水里的是死路（取水巷的端头落进河面格），半米级的湿点只是水沟。
            if (bestRun >= 4 && bestFrom > 0 && bestTo < samp.size() - 1) {
               double[] a = (double[])samp.get(bestFrom);
               double[] b = (double[])samp.get(bestTo);
               double bx = (a[0] + b[0]) / (double)2.0F;
               double bz = (a[1] + b[1]) / (double)2.0F;
               boolean dup = false;

               for(double[] d : done) {
                  if (Math.hypot(d[0] - bx, d[1] - bz) < (double)8.0F) {
                     dup = true;
                     break;
                  }
               }

               if (!dup) {
                  done.add(new double[]{bx, bz});
                  double blen = Math.max((double)6.0F, Math.hypot(b[0] - a[0], b[1] - a[1]) + (double)5.0F);
                  double ang = Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
                  this.features.add(new PlacedFeature(this.nextCode("f_bridge"), "桥", "水工", "bridge", (String)null, bx, bz, blen, Math.max((double)2.5F, r.width()), ang, 1.2, 1, this.t.seaLevel, (String)null, "#8a7350", (String)null, bx, bz, ang + (double)90.0F, this.rect(bx, bz, blen, Math.max((double)2.5F, r.width()), ang), "{\"over\":\"water\"}", (String)null, 0, 3));
               }
            }
         }
      }

   }

   /**
    * 房与房不允许相交（收尾兜底）。
    *
    * <p><b>为什么还要单独兜一次</b>：落位期的 {@code canPlace} 把轮廓按 1 米格网栅格化、
    * 逐格心查占用——两块斜放的矩形只在角上相交 0.1~0.3 ㎡ 时，相交区域里一个格心都没有，
    * 判据整格漏过去（实测 1 张图里出现「两家轮廓角部相交 0.1 ㎡」）。格网判据擅长"整块压住"，
    * 判不了"尖角擦过"，所以在多边形层面补一次。</p>
    *
    * <p>处理方式是<b>沿两栋中心连线后退</b>（步长 0.25 米、最多 3 米），找到与所有其他建筑
    * 都不相交的位置；退不开就保留原位并计数——宁可留一处理论瑕疵，也不让房子凭空消失
    * （每栋民居都绑着一户人家，删一栋就是删一家人）。</p>
    */
   private void separateBuildingOverlaps() {
      List<PlacedFeature> feats = this.features;
      int moved = 0;
      int unresolved = 0;

      for(int i = 0; i < feats.size(); ++i) {
         PlacedFeature a = (PlacedFeature)feats.get(i);
         if ("building".equals(a.kind()) && a.polygon() != null && a.polygon().length >= 3) {
            for(int j = i + 1; j < feats.size(); ++j) {
               PlacedFeature b = (PlacedFeature)feats.get(j);
               if (!"building".equals(b.kind()) || b.polygon() == null || b.polygon().length < 3) {
                  continue;
               }

               if (!polysIntersect(a.polygon(), b.polygon())) {
                  continue;
               }

               double ux = b.x() - a.x();
               double uz = b.z() - a.z();
               double len = Math.hypot(ux, uz);
               if (len < 1.0E-6) {
                  ux = Math.cos(Math.toRadians(b.rot()));
                  uz = Math.sin(Math.toRadians(b.rot()));
               } else {
                  ux /= len;
                  uz /= len;
               }

               boolean placed = false;
               for(double t = 0.25; t <= 3.0; t += 0.25) {
                  double nx = b.x() + ux * t;
                  double nz = b.z() + uz * t;
                  double[][] np = this.rect(nx, nz, b.w(), b.d(), b.rot());
                  if (this.polysHitAny(np, feats, b)) {
                     continue;
                  }

                  double dx = nx - b.x();
                  double dz = nz - b.z();
                  feats.set(j, new PlacedFeature(b.code(), b.name(), b.category(), b.kind(),
                          b.assetCode(), nx, nz, b.w(), b.d(), b.rot(), b.height(), b.floors(),
                          b.baseElevation(), b.wallColor(), b.roofColor(), b.roofShape(),
                          b.doorX() + dx, b.doorZ() + dz, b.doorDir(), np, b.semanticJson(),
                          b.locationCode(), b.residency(), b.importance()));
                  ++moved;
                  placed = true;
                  break;
               }

               if (!placed) {
                  ++unresolved;
               }
            }
         }
      }

      if (moved > 0) {
         this.diag.merge("房距微调", moved, Integer::sum);
      }

      if (unresolved > 0) {
         this.diag.merge("房距未解", unresolved, Integer::sum);
      }

   }

   /** 给定轮廓是否与「除自己以外」的任何建筑轮廓相交。 */
   private boolean polysHitAny(double[][] poly, List<PlacedFeature> feats, PlacedFeature self) {
      for(PlacedFeature o : feats) {
         if (o != self && "building".equals(o.kind()) && o.polygon() != null
                 && o.polygon().length >= 3 && polysIntersect(poly, o.polygon())) {
            return true;
         }
      }

      return false;
   }

   /** 两个多边形是否有正面积相交（边相交或一方顶点落在另一方内部即算）。 */
   private static boolean polysIntersect(double[][] a, double[][] b) {
      int n = a.length;
      int m = b.length;

      for(int i = 0; i < n; ++i) {
         double[] a0 = a[i];
         double[] a1 = a[(i + 1) % n];

         for(int j = 0; j < m; ++j) {
            if (segmentsCross(a0, a1, b[j], b[(j + 1) % m])) {
               return true;
            }
         }
      }

      return pointInPoly(a[0][0], a[0][1], b) || pointInPoly(b[0][0], b[0][1], a);
   }

   /** 两条线段是否真正相交（端点接触不算）。 */
   private static boolean segmentsCross(double[] p1, double[] p2, double[] p3, double[] p4) {
      double d1 = crossOf(p3, p4, p1);
      double d2 = crossOf(p3, p4, p2);
      double d3 = crossOf(p1, p2, p3);
      double d4 = crossOf(p1, p2, p4);
      return (d1 > (double)0.0F && d2 < (double)0.0F || d1 < (double)0.0F && d2 > (double)0.0F)
              && (d3 > (double)0.0F && d4 < (double)0.0F || d3 < (double)0.0F && d4 > (double)0.0F);
   }

   /** 向量 ab × ap 的叉积（符号即 p 在 ab 的哪一侧）。 */
   private static double crossOf(double[] a, double[] b, double[] p) {
      return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
   }

   /**
    * 一条线段是否穿过任何一块铺装地（广场/货场）。
    *
    * <p>入户小径是在最后一步才补的，那时货场/广场早已落位；不查这一步，小径会从场地中间
    * 斜穿过去——图上就是"铺装地被一条土路劈成两半"（实测 1 例：镇中心广场被两条入户小径横穿）。
    * 场地边界只是贴边不算穿（判据用严格相交），所以"路沿场地边过"仍然允许。</p>
    */
   private boolean segmentCrossesPaved(double x1, double z1, double x2, double z2) {
      double[] p1 = new double[]{x1, z1};
      double[] p2 = new double[]{x2, z2};

      for(PlacedFeature f : this.features) {
         if (!"plaza".equals(f.kind()) && !"quay".equals(f.kind())) {
            continue;
         }

         double[][] poly = f.polygon();
         if (poly == null || poly.length < 3) {
            continue;
         }

         for(int i = 0; i < poly.length; ++i) {
            if (segmentsCross(p1, p2, poly[i], poly[(i + 1) % poly.length])) {
               return true;
            }
         }

         if (pointInPoly(x1, z1, poly) || pointInPoly(x2, z2, poly)) {
            return true;
         }
      }

      return false;
   }

   /**
    * 门朝向收口：{@code door_dir} 的唯一口径 = 门所在轮廓边的<b>外法向</b>
    * （度，x 轴起算、z 向为正，即方向向量 {@code (cos θ, sin θ)}）。
    *
    * <p><b>为什么不在各处落位时算好</b>：沿街房、契约住所、院落厢房、路头小屯、契约设施
    * 这 5 条落位路径各写一套 doorDir 表达式，口径必然漂移——实测一半建筑的字段值与墙面法向
    * 相反（门坐标本身是对的，但任何按 door_dir 推「门朝哪」的下游都会反：寻路、日程、
    * 前端门洞）。收口成一个几何定义后，字段与几何不可能再漂移。</p>
    */
   private void normalizeDoorDirections() {
      List<PlacedFeature> out = new ArrayList(this.features.size());
      int fixed = 0;

      for(PlacedFeature f : this.features) {
         double[][] poly = f.polygon();
         if (!"building".equals(f.kind()) || poly == null || poly.length < 3
                 || f.doorX() == (double)0.0F && f.doorZ() == (double)0.0F) {
            out.add(f);
         } else {
            double deg = doorOutwardDeg(poly, f.doorX(), f.doorZ());
            if (Double.isNaN(deg)) {
               out.add(f);
            } else {
               ++fixed;
               out.add(new PlacedFeature(f.code(), f.name(), f.category(), f.kind(), f.assetCode(),
                       f.x(), f.z(), f.w(), f.d(), f.rot(), f.height(), f.floors(), f.baseElevation(),
                       f.wallColor(), f.roofColor(), f.roofShape(), f.doorX(), f.doorZ(), deg, poly,
                       f.semanticJson(), f.locationCode(), f.residency(), f.importance()));
            }
         }
      }

      this.features.clear();
      this.features.addAll(out);
      this.diag.put("门朝归一", fixed);
   }

   /**
    * 点所在轮廓边的外法向（度）。
    *
    * @param poly 轮廓（米）
    * @param px   门点 X
    * @param pz   门点 Z
    * @return 外法向角度（0~360）；轮廓退化时 NaN
    */
   private static double doorOutwardDeg(double[][] poly, double px, double pz) {
      int n = poly.length;
      int edge = -1;
      double best = Double.MAX_VALUE;

      for(int i = 0; i < n; ++i) {
         double[] a = poly[i];
         double[] b = poly[(i + 1) % n];
         double ex = b[0] - a[0];
         double ez = b[1] - a[1];
         double l2 = ex * ex + ez * ez;
         double t = l2 <= 1.0E-9 ? (double)0.0F : ((px - a[0]) * ex + (pz - a[1]) * ez) / l2;
         t = Math.max((double)0.0F, Math.min((double)1.0F, t));
         double d = Math.hypot(px - (a[0] + ex * t), pz - (a[1] + ez * t));
         if (d < best) {
            best = d;
            edge = i;
         }
      }

      if (edge < 0) {
         return Double.NaN;
      } else {
         double[] a = poly[edge];
         double[] b = poly[(edge + 1) % n];
         double ex = b[0] - a[0];
         double ez = b[1] - a[1];
         double l = Math.hypot(ex, ez);
         if (l < 1.0E-9) {
            return Double.NaN;
         } else {
            double nx = -ez / l;
            double nz = ex / l;
            double cx = (double)0.0F;
            double cz = (double)0.0F;

            for(double[] p : poly) {
               cx += p[0];
               cz += p[1];
            }

            cx /= (double)n;
            cz /= (double)n;
            double mx = (a[0] + b[0]) / (double)2.0F;
            double mz = (a[1] + b[1]) / (double)2.0F;
            if (nx * (mx - cx) + nz * (mz - cz) < (double)0.0F) {
               nx = -nx;
               nz = -nz;
            }

            double deg = Math.toDegrees(Math.atan2(nz, nx));
            return deg < (double)0.0F ? deg + (double)360.0F : deg;
         }
      }
   }

   /**
    * 桥的「标记 / 要素」对账：把「标了 bridge_over=water，却没有任何桥要素」的路标记清掉。
    *
    * <p>标记来自 S3 的 A* 过水格数，而桥要素要求过水段<b>夹在路中间</b>（两头都在岸上）。
    * 两者口径不同，典型差异是「取水巷的端头多走一两米落进河面」：有标记、不该有桥，
    * 旧数据里就出现一张图带着 {@code bridge_over=water}、前端却找不到桥的自相矛盾。
    * 这里按要素反查：路廊 9 米内没有桥要素的，把标记清零——宁可标"无桥"，也不留矛盾数据。</p>
    */
   private void reconcileBridgeFlags(List<RoadSpec> roads) {
      int cleared = 0;

      for(int i = 0; i < roads.size(); ++i) {
         RoadSpec r = roads.get(i);
         if (r.bridgeCells() > 0 && !this.hasBridgeNear(r.pathMeters(), 9.0)) {
            roads.set(i, new RoadSpec(r.code(), r.name(), r.kind(), r.grade(), r.width(),
                    r.surface(), r.pathMeters(), 0));
            ++cleared;
         }
      }

      if (cleared > 0) {
         this.diag.merge("桥标记对账", cleared, Integer::sum);
      }
   }

   /** 路廊（按 2 米采样）附近是否已有桥要素。 */
   private boolean hasBridgeNear(List<double[]> path, double range) {
      if (path == null || path.size() < 2) {
         return false;
      }

      for(PlacedFeature f : this.features) {
         if (!"bridge".equals(f.kind())) {
            continue;
         }

         for(int i = 0; i < path.size() - 1; ++i) {
            double[] a = path.get(i);
            double[] b = path.get(i + 1);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int n = Math.max(1, (int)Math.ceil(len / 2.0));

            for(int k = 0; k <= n; ++k) {
               double t = (double)k / (double)n;
               double x = a[0] + (b[0] - a[0]) * t;
               double z = a[1] + (b[1] - a[1]) * t;
               if (Math.hypot(x - f.x(), z - f.z()) <= range) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private Integer capForSemantic(String key) {
      Integer var10000;
      switch (key) {
         case "lighthouse" -> var10000 = 1;
         case "school" -> var10000 = 1;
         case "hall" -> var10000 = 1;
         case "pier" -> var10000 = 1;
         case "mill" -> var10000 = 1;
         case "well" -> var10000 = Math.max(1, (this.budget == null ? 12 : Math.max(1, this.budget.households())) / 6);
         default -> var10000 = null;
      }

      return var10000;
   }

   private static String capKey(AssetSpec a) {
      return capKey(a.code(), a.name());
   }

   private static String capKey(String code, String name) {
      String s = ((code == null ? "" : code) + " " + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
      if (!s.contains("lighthouse") && !s.contains("灯塔") && !s.contains("tower")) {
         if (!s.contains("school") && !s.contains("塾") && !s.contains("学堂")) {
            if (!s.contains("hall") && !s.contains("堂") && !s.contains("议事")) {
               if (!s.contains("pier") && !s.contains("码头") && !s.contains("渡口")) {
                  if (!s.contains("mill") && !s.contains("磨")) {
                     return !s.contains("well") && !s.contains("井") ? "" : "well";
                  } else {
                     return "mill";
                  }
               } else {
                  return "pier";
               }
            } else {
               return "hall";
            }
         } else {
            return "school";
         }
      } else {
         return "lighthouse";
      }
   }

   private int countBuilt() {
      int n = 0;

      for(PlacedFeature f : this.features) {
         if ("building".equals(f.kind())) {
            ++n;
         }
      }

      return n;
   }

   private boolean budgetFull() {
      return this.budget != null && this.countBuilt() >= this.budget.total();
   }

   private boolean dwellingsFull() {
      return this.budget != null && this.builtDwellings >= Math.max(1, this.budget.households());
   }

   private boolean outbuildingsFull() {
      return this.budget != null && this.builtOutbuildings >= Math.max(0, this.budget.outbuildings());
   }

   private boolean categoryFull(String category) {
      if (this.budget != null && category != null) {
         int cap = this.budget.capFor(category);
         int have = 0;

         for(PlacedFeature f : this.features) {
            if ("building".equals(f.kind()) && category.equals(f.category())) {
               ++have;
            }
         }

         return have >= cap;
      } else {
         return false;
      }
   }

   private boolean assetFull(AssetSpec a) {
      if (this.budget == null) {
         return false;
      } else if ("民居".equals(a.category())) {
         return this.dwellingsFull();
      } else if (this.facilityAssets.contains(a.code())) {
         return true;
      } else if (this.categoryFull(a.category())) {
         return true;
      } else {
         Integer cap = this.capForSemantic(capKey(a));
         if (cap == null) {
            return false;
         } else {
            String key = capKey(a);
            int have = 0;

            for(PlacedFeature p : this.features) {
               if ("building".equals(p.kind()) && key.equals(capKey(p.assetCode(), p.name()))) {
                  ++have;
               }
            }

            return have >= cap;
         }
      }
   }

   private AssetSpec pickSpec(List<AssetSpec> assets, double distRatio, boolean major, boolean inCore, boolean alley) {
      double roll = this.rng.nextDouble();
      String want;
      if (alley) {
         want = roll < 0.64 ? "民居" : (roll < 0.88 ? "工坊" : "生产");
      } else if (inCore) {
         want = roll < 0.45 ? "公共" : (roll < 0.85 ? "商铺" : "民居");
      } else if (distRatio < 0.14) {
         want = roll < 0.4 ? "商铺" : (roll < 0.7 ? "民居" : (roll < 0.88 ? "公共" : "工坊"));
      } else if (distRatio < 0.26) {
         want = roll < 0.62 ? "民居" : (roll < 0.82 ? "商铺" : (roll < 0.94 ? "工坊" : "生产"));
      } else {
         want = roll < 0.55 ? "民居" : (roll < 0.78 ? "工坊" : "生产");
      }

      if (!major && "商铺".equals(want) && this.rng.nextDouble() < (double)0.5F) {
         want = "民居";
      }

      List<AssetSpec> pool = new ArrayList();
      int total = 0;

      for(AssetSpec a : assets) {
         if ("building".equals(a.kind()) && want.equals(a.category()) && !this.assetFull(a)) {
            pool.add(a);
            total += Math.max(1, a.weight());
         }
      }

      if (pool.isEmpty()) {
         for(AssetSpec a : assets) {
            if ("building".equals(a.kind()) && !this.assetFull(a)) {
               pool.add(a);
               total += Math.max(1, a.weight());
            }
         }
      }

      if (pool.isEmpty()) {
         return null;
      } else {
         int pick = this.rng.nextInt(Math.max(1, total));
         int acc = 0;

         for(AssetSpec a : pool) {
            acc += Math.max(1, a.weight());
            if (pick < acc) {
               return a;
            }
         }

         return (AssetSpec)pool.get(pool.size() - 1);
      }
   }

   private AssetSpec findKind(List<AssetSpec> assets, String kind) {
      for(AssetSpec a : assets) {
         if (kind.equals(a.kind())) {
            return a;
         }
      }

      return null;
   }

   private static String genericBuildingName(String category, double x, double z) {
      String[] pool = (String[])GENERIC_BUILDING_NAMES.getOrDefault(category, GENERIC_FALLBACK_NAMES);
      int h = Objects.hash(new Object[]{category, Math.round(x * (double)0.5F), Math.round(z * (double)0.5F)});
      return pool[Math.abs(h) % pool.length];
   }

   private static String nameOr(AssetSpec spec, double x, double z) {
      String n = spec.name();
      return n != null && !n.isBlank() ? n : genericBuildingName(spec.category(), x, z);
   }

   private String maybeName(AssetSpec spec, double x, double z) {
      String ai = spec.name();
      boolean hasAi = ai != null && !ai.isBlank();
      boolean notable = "公共".equals(spec.category()) || "商铺".equals(spec.category()) || "工坊".equals(spec.category()) || "生产".equals(spec.category()) || "水工".equals(spec.category());
      if (notable) {
         return hasAi ? ai : genericBuildingName(spec.category(), x, z);
      } else {
         return hasAi && this.rng.nextDouble() < 0.35 ? ai : genericBuildingName(spec.category(), x, z);
      }
   }

   private static String rearRoof(String hex) {
      if (hex != null && hex.length() == 7 && hex.charAt(0) == '#') {
         try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            int grey = 118;
            int nr = (int)Math.round(((double)r + (double)(grey - r) * 0.38) * 0.9);
            int ng = (int)Math.round(((double)g + (double)(grey - g) * 0.38) * 0.9);
            int nb = (int)Math.round(((double)b + (double)(grey - b) * 0.38) * 0.9);
            return String.format("#%02x%02x%02x", clamp255(nr), clamp255(ng), clamp255(nb));
         } catch (NumberFormatException var8) {
            return hex;
         }
      } else {
         return hex;
      }
   }

   private static int clamp255(int v) {
      return Math.max(0, Math.min(255, v));
   }

   private boolean canPlace(double cx, double cz, double w, double d, double rotDeg) {
      return this.canPlace(cx, cz, w, d, rotDeg, 0.05F);
   }

   private boolean canPlace(double cx, double cz, double w, double d, double rotDeg, float minBuildable) {
      String why = this.blockedPoly(this.rect(cx, cz, w + (double)0.5F, d + (double)0.5F, rotDeg), false, minBuildable);
      if (why != null) {
         this.bump(why);
         return false;
      } else {
         return true;
      }
   }

   private String blockedPoly(double[][] poly) {
      return this.blockedPoly(poly, false);
   }

   private String blockedPolyForFarm(double[][] poly) {
      return this.blockedPoly(poly, true);
   }

   /**
    * 田块专用校验：不但要「没被占」，还得是<b>能下犁的地</b>。
    *
    * <p>为什么不能直接用 {@link #blockedPolyForFarm}：那条路径只看高程/坡度/占用，
    * 于是条田会被种到密林和岩地里去。真实世界里没人开垦林下——而渲染时树冠是画在田之上的，
    * 结果就是「田地埋在树叶底下」，一眼假。所以这里额外查三项：</p>
    * <ol>
    *   <li>群系必须是开阔地（草地/草甸/既有农田，见 {@link #plowable}），且占这块地的 60% 以上；</li>
    *   <li>与已有田块的重叠不得超过 20%（田埂可以贴边，但田不能压田）；</li>
    *   <li>其余仍按 {@link #blockedCell} 判越界/占用。</li>
    * </ol>
    *
    * @param poly 田块多边形（米）
    * @return 不可用的原因（用于诊断计数）；可用返回 null
    */
   private String blockedFarmPlot(double[][] poly) {
      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minZ = Double.MAX_VALUE;
      double maxZ = -Double.MAX_VALUE;

      for(double[] pt : poly) {
         minX = Math.min(minX, pt[0]);
         maxX = Math.max(maxX, pt[0]);
         minZ = Math.min(minZ, pt[1]);
         maxZ = Math.max(maxZ, pt[1]);
      }

      int x0 = (int)Math.floor(minX / this.mpc);
      int x1 = (int)Math.ceil(maxX / this.mpc);
      int z0 = (int)Math.floor(minZ / this.mpc);
      int z1 = (int)Math.ceil(maxZ / this.mpc);
      int seen = 0;
      int plow = 0;
      int overlap = 0;

      for(int z = z0; z <= z1; ++z) {
         for(int x = x0; x <= x1; ++x) {
            if (x < 1 || z < 1 || x >= this.size - 1 || z >= this.size - 1) {
               return "越界";
            }

            if (pointInPoly(((double)x + (double)0.5F) * this.mpc, ((double)z + (double)0.5F) * this.mpc, poly)) {
               int i = z * this.size + x;
               ++seen;
               if (this.fieldCell != null && this.fieldCell[i]) {
                  ++overlap;
                  ++plow;
               } else {
                  String why = this.blockedCell(i, 0.05F);
                  if (why != null) {
                     return why;
                  }

                  if (this.plowable(i)) {
                     ++plow;
                  }
               }
            }
         }
      }

      if (seen <= 0) {
         return null;
      } else if ((double)overlap / (double)seen > 0.35) {
         // 整段成片算法会让相邻 band 贴边，20% 的重叠门槛太敏感（一整段会被判"叠"）；
         // 放宽到 35%，但**必须保留**：否则两条田会压在一起，图上就是糊成一块的黄斑。
         return "叠";
      } else {
         return (double)plow / (double)seen < 0.5 ? "林" : null;
      }
   }

   private String blockedPoly(double[][] poly, boolean allowFieldCells) {
      return this.blockedPoly(poly, allowFieldCells, 0.05F);
   }

   private String blockedPoly(double[][] poly, boolean allowFieldCells, float minBuildable) {
      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minZ = Double.MAX_VALUE;
      double maxZ = -Double.MAX_VALUE;

      for(double[] pt : poly) {
         minX = Math.min(minX, pt[0]);
         maxX = Math.max(maxX, pt[0]);
         minZ = Math.min(minZ, pt[1]);
         maxZ = Math.max(maxZ, pt[1]);
      }

      int x0 = (int)Math.floor(minX / this.mpc);
      int x1 = (int)Math.ceil(maxX / this.mpc);
      int z0 = (int)Math.floor(minZ / this.mpc);
      int z1 = (int)Math.ceil(maxZ / this.mpc);

      for(int z = z0; z <= z1; ++z) {
         for(int x = x0; x <= x1; ++x) {
            if (x < 1 || z < 1 || x >= this.size - 1 || z >= this.size - 1) {
               return "越界";
            }

            if (pointInPoly(((double)x + (double)0.5F) * this.mpc, ((double)z + (double)0.5F) * this.mpc, poly)) {
               int i = z * this.size + x;
               if (!allowFieldCells || this.fieldCell == null || !this.fieldCell[i]) {
                  String why = this.blockedCell(i, minBuildable);
                  if (why != null) {
                     return why;
                  }
               }
            }
         }
      }

      return null;
   }

   private void markFieldPoly(double[][] poly) {
      if (this.fieldCell == null) {
         this.fieldCell = new boolean[this.size * this.size];
      }

      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minZ = Double.MAX_VALUE;
      double maxZ = -Double.MAX_VALUE;

      for(double[] pt : poly) {
         minX = Math.min(minX, pt[0]);
         maxX = Math.max(maxX, pt[0]);
         minZ = Math.min(minZ, pt[1]);
         maxZ = Math.max(maxZ, pt[1]);
      }

      for(int z = (int)Math.floor(minZ / this.mpc); z <= (int)Math.ceil(maxZ / this.mpc); ++z) {
         for(int x = (int)Math.floor(minX / this.mpc); x <= (int)Math.ceil(maxX / this.mpc); ++x) {
            if (this.okInBounds(x, z) && pointInPoly(((double)x + (double)0.5F) * this.mpc, ((double)z + (double)0.5F) * this.mpc, poly)) {
               this.fieldCell[z * this.size + x] = true;
            }
         }
      }

   }

   private String blockedCell(int i) {
      return this.blockedCell(i, 0.05F);
   }

   private String blockedCell(int i, float minBuildable) {
      if (this.occ[i] > 0.35F) {
         return "占用";
      } else if (this.t.waterCell(i)) {
         // 水陆唯一入口（TerrainData.waterCell）：内陆湖水面高于海平面，按高程判会把湖当旱地。
         return "水面";
      } else {
         return this.t.buildable[i] < minBuildable ? "不可建" : null;
      }
   }

   private boolean[] builtClearance(double clearance) {
      return this.dilate(this.builtOccupancy(), clearance);
   }

   private float[] builtOccupancy() {
      // 缓存必须跟 feature 数量绑定：这一层占用场在「广场/设施」阶段就会被第一次算出来，
      // 如果一直用这份快照，之后落位的民居/厢房/院墙全都不在里面——A* 的「建筑净空」
      // 就等于没开，后修的连接路会从已经盖好的房子身上穿过去。
      if (this.builtOcc != null && this.builtOccCount == this.features.size()) {
         return this.builtOcc;
      } else {
         float[] m = new float[this.size * this.size];

         for(PlacedFeature f : this.features) {
            if ("building".equals(f.kind()) || "plaza".equals(f.kind())) {
               double[][] poly = f.polygon() != null ? f.polygon() : this.rect(f.x(), f.z(), f.w(), f.d(), f.rot());
               this.rasterPolyTo(poly, m, 1.0F);
            }
         }

         this.builtOcc = m;
         this.builtOccCount = this.features.size();
         return this.builtOcc;
      }
   }

   private boolean[] dilate(float[] m, double clearance) {
      int rad = (int)Math.ceil(clearance / this.mpc);
      boolean[] out = new boolean[this.size * this.size];

      for(int z = 0; z < this.size; ++z) {
         for(int x = 0; x < this.size; ++x) {
            if (!(m[z * this.size + x] <= 0.05F)) {
               for(int dz = -rad; dz <= rad; ++dz) {
                  for(int dx = -rad; dx <= rad; ++dx) {
                     int xx = x + dx;
                     int zz = z + dz;
                     if (xx >= 0 && zz >= 0 && xx < this.size && zz < this.size && !((double)(dx * dx + dz * dz) > (double)(rad * rad) + 1.0E-9)) {
                        out[zz * this.size + xx] = true;
                     }
                  }
               }
            }
         }
      }

      return out;
   }

   private float[] costWithClearance() {
      float[] built = this.builtOccupancy();
      boolean[] ring = this.dilate(built, 2.6);
      float[] cost = RoadRouter.buildCost(this.t, built, ring);

      for(int i = 0; i < cost.length; ++i) {
         if (ring[i]) {
            cost[i] += 200.0F;
         }
      }

      return cost;
   }

   private boolean farmable(int i) {
      return !this.t.waterCell(i) && (double)this.t.height[i] > this.t.seaLevel + 0.004 && (double)this.t.buildable[i] >= 0.08 && (double)this.t.slope[i] <= 0.26 && this.occ[i] <= 0.05F;
   }

   /**
    * 能不能下犁：{@link #farmable} 之外再要求<b>开阔地</b>（草地/草甸/既有农田）。
    *
    * <p>为什么必须卡群系：渲染时树冠是画在地面之上的，把条田种进密林/疏林，
    * 出来就是「田地埋在树叶底下」——旧图里那些斜插在林中的黄条就是这么来的。
    * 真实世界里没人会去林下开条田：地是开在村子周围的草甸上，林子留着打柴。</p>
    *
    * @param i 格索引
    * @return true=可开垦
    */
   private boolean plowable(int i) {
      if (!this.farmable(i)) {
         return false;
      }
      byte b = this.t.biome[i];
      return b == Biomes.GRASS || b == Biomes.MEADOW || b == Biomes.FARMLAND;
   }

   private double[] nearestRoadPoint(List<RoadSpec> roads, double x, double z) {
      return this.nearestRoadPoint(roads, x, z, 0);
   }

   private double[] nearestRoadPoint(List<RoadSpec> roads, double x, double z, int minGrade) {
      double best = Double.MAX_VALUE;
      double bx = x;
      double bz = z;

      for(RoadSpec r : roads) {
         if (r.pathMeters() != null && r.pathMeters().size() >= 2 && r.grade() >= minGrade) {
            double[] q = MapNetwork.nearestOnPath(r.pathMeters(), x, z);
            if (q[2] < best) {
               best = q[2];
               bx = q[0];
               bz = q[1];
            }
         }
      }

      return best == Double.MAX_VALUE ? null : new double[]{bx, bz, best};
   }

   private boolean touchesRoad(List<RoadSpec> roads, double x, double z, double maxDist) {
      double[] q = this.nearestRoadPoint(roads, x, z);
      return q != null && q[2] <= maxDist;
   }

   private void rasterPoly(double[][] poly, float v) {
      this.rasterPolyTo(poly, this.occ, v);
   }

   private void rasterPolyTo(double[][] poly, float[] target, float v) {
      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minZ = Double.MAX_VALUE;
      double maxZ = -Double.MAX_VALUE;

      for(double[] pt : poly) {
         minX = Math.min(minX, pt[0]);
         maxX = Math.max(maxX, pt[0]);
         minZ = Math.min(minZ, pt[1]);
         maxZ = Math.max(maxZ, pt[1]);
      }

      int x0 = (int)Math.floor(minX / this.mpc);
      int x1 = (int)Math.ceil(maxX / this.mpc);
      int z0 = (int)Math.floor(minZ / this.mpc);
      int z1 = (int)Math.ceil(maxZ / this.mpc);

      for(int z = z0; z <= z1; ++z) {
         for(int x = x0; x <= x1; ++x) {
            if (this.okInBounds(x, z) && pointInPoly(((double)x + (double)0.5F) * this.mpc, ((double)z + (double)0.5F) * this.mpc, poly)) {
               int i = z * this.size + x;
               target[i] = Math.max(target[i], v);
            }
         }
      }

   }

   private static double polyArea(double[][] poly) {
      double s = (double)0.0F;
      int i = 0;

      for(int j = poly.length - 1; i < poly.length; j = i++) {
         s += poly[j][0] * poly[i][1] - poly[i][0] * poly[j][1];
      }

      return Math.abs(s) / (double)2.0F;
   }

   private double[] gradient(double x, double z) {
      int i = this.cellIndex(x, z);
      if (i < 0) {
         return new double[]{(double)0.0F, (double)0.0F};
      } else {
         int cx = i % this.size;
         int cz = i / this.size;
         int x0 = Math.max(0, cx - 1);
         int x1 = Math.min(this.size - 1, cx + 1);
         int z0 = Math.max(0, cz - 1);
         int z1 = Math.min(this.size - 1, cz + 1);
         double dx = (double)(this.t.smooth[cz * this.size + x1] - this.t.smooth[cz * this.size + x0]) / Math.max(1.0E-6, (double)(x1 - x0) * this.mpc);
         double dz = (double)(this.t.smooth[z1 * this.size + cx] - this.t.smooth[z0 * this.size + cx]) / Math.max(1.0E-6, (double)(z1 - z0) * this.mpc);
         return new double[]{dx, dz};
      }
   }

   private double[][] irregularPlot(double cx, double cz, double w, double d, double rotDeg) {
      double hw = w / (double)2.0F;
      double hd = d / (double)2.0F;
      double jx = hw * 0.13;
      double jz = hd * 0.13;
      double[][] local = new double[][]{{-hw + this.rng.nextDouble(-jx, jx), -hd + this.rng.nextDouble(-jz, jz)}, {this.rng.nextDouble(-jx, jx), -hd - this.rng.nextDouble((double)0.0F, jz * 1.2)}, {hw + this.rng.nextDouble(-jx, jx), -hd + this.rng.nextDouble(-jz, jz)}, {hw + this.rng.nextDouble(-jx, jx), hd + this.rng.nextDouble(-jz, jz)}, {this.rng.nextDouble(-jx, jx), hd + this.rng.nextDouble((double)0.0F, jz * 1.2)}, {-hw + this.rng.nextDouble(-jx, jx), hd + this.rng.nextDouble(-jz, jz)}};
      double rad = Math.toRadians(rotDeg);
      double cos = Math.cos(rad);
      double sin = Math.sin(rad);
      double[][] out = new double[local.length][2];

      for(int i = 0; i < local.length; ++i) {
         out[i][0] = cx + local[i][0] * cos - local[i][1] * sin;
         out[i][1] = cz + local[i][0] * sin + local[i][1] * cos;
      }

      return out;
   }

   private static double angDiff(double a, double b) {
      double dd;
      for(dd = a - b; dd > Math.PI; dd -= (Math.PI * 2D)) {
      }

      while(dd < -Math.PI) {
         dd += (Math.PI * 2D);
      }

      return dd;
   }

   private static boolean pointInPoly(double x, double z, double[][] poly) {
      boolean inside = false;
      int i = 0;

      for(int j = poly.length - 1; i < poly.length; j = i++) {
         double xi = poly[i][0];
         double zi = poly[i][1];
         double xj = poly[j][0];
         double zj = poly[j][1];
         if (zi > z != zj > z && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
            inside = !inside;
         }
      }

      return inside;
   }

   private void commit(double cx, double cz, double w, double d, double rotDeg) {
      this.rasterRect(cx, cz, w, d, rotDeg, 1.0F);
   }

   private void stampCircle(double cx, double cz, double r, float v) {
      int ix = (int)Math.round(cx / this.mpc);
      int iz = (int)Math.round(cz / this.mpc);
      int ir = (int)Math.ceil(r / this.mpc);

      for(int dz = -ir; dz <= ir; ++dz) {
         for(int dx = -ir; dx <= ir; ++dx) {
            int x = ix + dx;
            int z = iz + dz;
            if (x >= 0 && z >= 0 && x < this.size && z < this.size && this.okInBounds(x, z)) {
               this.occ[z * this.size + x] = Math.max(this.occ[z * this.size + x], v);
            }
         }
      }

   }

   private void rasterRect(double cx, double cz, double w, double d, double rotDeg, float v) {
      double rad = Math.toRadians(rotDeg);
      double cos = Math.cos(rad);
      double sin = Math.sin(rad);
      double r = Math.hypot(w, d) / (double)2.0F;
      int ir = (int)Math.ceil(r / this.mpc) + 1;
      int ix = (int)Math.floor(cx / this.mpc);
      int iz = (int)Math.floor(cz / this.mpc);

      for(int dz = -ir; dz <= ir; ++dz) {
         for(int dx = -ir; dx <= ir; ++dx) {
            int x = ix + dx;
            int z = iz + dz;
            if (this.okInBounds(x, z)) {
               double px = ((double)x + (double)0.5F) * this.mpc - cx;
               double pz = ((double)z + (double)0.5F) * this.mpc - cz;
               double lx = px * cos + pz * sin;
               double lz = -px * sin + pz * cos;
               if (Math.abs(lx) <= w / (double)2.0F && Math.abs(lz) <= d / (double)2.0F) {
                  this.occ[z * this.size + x] = Math.max(this.occ[z * this.size + x], v);
               }
            }
         }
      }

   }

   private boolean okInBounds(int x, int z) {
      return x >= 0 && z >= 0 && x < this.size && z < this.size;
   }

   private int cellIndex(double x, double z) {
      int cx = (int)Math.floor(x / this.mpc);
      int cz = (int)Math.floor(z / this.mpc);
      return cx >= 0 && cz >= 0 && cx < this.size && cz < this.size ? cz * this.size + cx : -1;
   }

   private double elevationAt(double x, double z) {
      int i = this.cellIndex(x, z);
      return i < 0 ? (double)0.0F : Math.max((double)0.0F, ((double)this.t.height[i] - this.t.seaLevel) * this.t.heightToMeters);
   }

   private double[][] rect(double cx, double cz, double w, double d, double rotDeg) {
      double rad = Math.toRadians(rotDeg);
      double cos = Math.cos(rad);
      double sin = Math.sin(rad);
      double hw = w / (double)2.0F;
      double hd = d / (double)2.0F;
      double[][] local = new double[][]{{-hw, -hd}, {hw, -hd}, {hw, hd}, {-hw, hd}};
      double[][] out = new double[4][2];

      for(int i = 0; i < 4; ++i) {
         out[i][0] = cx + local[i][0] * cos - local[i][1] * sin;
         out[i][1] = cz + local[i][0] * sin + local[i][1] * cos;
      }

      return out;
   }

   private String nextCode(String prefix) {
      return prefix + "_" + ++this.seq;
   }

   public static Map<String, Object> summarize(Result r) {
      Map<String, Object> m = new LinkedHashMap();
      m.put("groups", r.groups().size());
      m.put("features", r.features().size());
      m.put("parcels", r.parcels().size());
      return m;
   }

   private static record DwellingSite(String homeRef, String homeName, String code, double x, double z, double doorX, double doorZ, String anchor, int members) {
   }

   public static record PlacedGroup(String code, String name, String kind, double cx, double cz, double radius, double rotation, String styleCode, String description) {
   }

   public static record PlacedFeature(String code, String name, String category, String kind, String assetCode, double x, double z, double w, double d, double rot, double height, int floors, double baseElevation, String wallColor, String roofColor, String roofShape, double doorX, double doorZ, double doorDir, double[][] polygon, String semanticJson, String locationCode, int residency, int importance) {
   }

   public static record PlacedParcel(String code, String kind, double cx, double cz, double area, double buildable, double[][] polygon, String roadCode, String featureCode) {
   }

   public static record Result(List<PlacedGroup> groups, List<PlacedFeature> features, List<PlacedParcel> parcels, List<RoadSpec> roads, float[] occupancy, Map<String, Integer> diag) {
   }

   public static record AssetSpec(String code, String name, String category, String kind, double baseW, double baseD, int floorsMin, int floorsMax, String wallColor, String roofColor, String roofShape, String material, int weight) {
   }

   public static record PlacementBudget(int households, int outbuildings, int shops, int workshops, int facilities, int water, int vacant, int residents) {
      public int dwellings() {
         return Math.max(1, this.households) + Math.max(0, this.outbuildings);
      }

      public int total() {
         return this.dwellings() + Math.max(0, this.shops) + Math.max(0, this.workshops) + Math.max(0, this.facilities) + Math.max(0, this.water) + Math.max(0, this.vacant);
      }

      public int capFor(String category) {
         if (category == null) {
            return 2;
         } else {
            int var10000;
            switch (category) {
               case "民居" -> var10000 = this.dwellings();
               case "商铺" -> var10000 = Math.max(1, this.shops);
               case "工坊" -> var10000 = Math.max(1, this.workshops);
               case "公共" -> var10000 = Math.max(1, this.facilities);
               case "水工" -> var10000 = Math.max(1, this.water);
               case "生产" -> var10000 = Math.max(2, this.facilities);
               default -> var10000 = 2;
            }

            return var10000;
         }
      }

      public static PlacementBudget from(WorldMapBrief brief) {
         if (brief == null) {
            return null;
         } else {
            int households = Math.max(1, brief.dwellingTarget());
            int outbuildings = Math.max(0, brief.outbuildingTarget());
            int residents = brief.demography() == null ? households * 3 : brief.demography().residents();
            return new PlacementBudget(households, outbuildings, brief.shopTarget(), brief.workshopTarget(), Math.max(1, brief.facilityTarget()), Math.max(1, brief.waterTarget()), 2, Math.max(households, residents));
         }
      }
   }

   public static record RoadSpec(String code, String name, String kind, int grade, double width, String surface, List<double[]> pathMeters, int bridgeCells) {
      public String level() {
         return RoadGrade.level(this.grade);
      }

      public String gradeLabel() {
         return RoadGrade.label(this.grade);
      }
   }

   private static record Frontage(String road, int side, double s0, double s1, double wallOffset) {
   }

   private static final class ActorKinds {
      static final String BUILDING = "building";
      static final String FIELD = "field";
      static final String FOREST = "forest";
      static final String PIER = "pier";
      static final String BRIDGE = "bridge";
      static final String PLAZA = "plaza";
      static final String WALL = "wall";
   }
}
