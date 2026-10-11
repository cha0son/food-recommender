package foodrec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * 每日饮食推荐系统 —— 可运行 Demo（零依赖，只用 JDK）
 *
 * 启动: java -cp out foodrec.SparkServer [端口]
 *
 * 算法核心: score = alpha * 偏好分 + beta * 时间分 + 场景调整 + 惩罚 + 探索噪声
 *   时间分 novelty = 1 - exp(-deltaDays / lambda)   ← 越久没吃，分数越高，上限为 1
 */
public class SparkServer {

    // ======================= 常量与模型 =======================

    static final List<String> MEAL_TYPES = List.of("早餐", "午餐", "晚餐", "夜宵");
    static final List<String> WEATHERS = List.of("晴", "阴", "雨", "热", "冷");

    /** 一道菜 */
    record Dish(String name, String cuisine, String mealType, int spicy,
                int minutes, int calories, String tags) {
        String tagsOrDefault() { return tags == null ? "" : tags; }
    }

    /** 某个用户对某道菜的统计（key = 菜名） */
    static final class DishStat {
        int stars = 0;            // 用户基础评分 0~5，0 表示还没打过分
        int eatCount = 0;         // 累计吃过次数
        LocalDate lastEaten = null;  // 上次吃什么时间
        int goodFeedback = 0;     // 标记为"还想吃"的次数
        int badFeedback = 0;      // 标记为"吃腻了"的次数

        double avgFeedback() {
            int total = goodFeedback + badFeedback;
            if (total == 0) return 0.5;
            return (double) goodFeedback / total;
        }
    }

    /** 打分明细，用于前端展示"为什么推荐它" */
    static final class ScoreDetail {
        String dish;
        double preference;   // 偏好分 0~1
        double novelty;      // 时间分 0~1
        double scene;        // 场景调整（可正可负）
        double penalty;      // 惩罚分（正数，最后会减掉）
        double noise;        // 探索噪声
        double total;
        long daysSince;
        String explain;
    }

    static final class RecParams {
        double alpha = 0.65;      // 偏好分权重
        double beta = 0.30;       // 时间分权重
        double lambda = 3.0;      // 想念半衰期（天），越小越容易腻
        double gamma = 0.10;      // 场景分权重
        double noiseAmp = 0.03;   // 探索噪声幅度
        int excludeRecentDays = 3;// 最近几天吃过的直接排除
        int minPool = 8;          // 候选池保底数量：不够就自动放宽排除天数
        boolean includeRecent = false; // 调试用：允许把最近吃过的也列出来
    }

    // ======================= 菜品库 =======================

    static final List<Dish> DISHES = List.of(
        new Dish("番茄炒蛋", "家常", "午餐", 0, 15, 180, "快手,下饭"),
        new Dish("青椒肉丝", "家常", "午餐", 1, 20, 260, "下饭"),
        new Dish("红烧肉", "家常", "晚餐", 0, 60, 520, "硬菜,油腻"),
        new Dish("可乐鸡翅", "家常", "晚餐", 0, 30, 380, "甜口"),
        new Dish("土豆炖牛肉", "家常", "晚餐", 0, 70, 450, "炖菜,顶饱"),
        new Dish("鱼香肉丝", "川菜", "午餐", 2, 25, 320, "下饭,酸甜"),
        new Dish("麻婆豆腐", "川菜", "晚餐", 3, 20, 280, "下饭,麻辣"),
        new Dish("水煮肉片", "川菜", "晚餐", 3, 35, 480, "麻辣,重口"),
        new Dish("宫保鸡丁", "川菜", "午餐", 2, 25, 350, "下饭"),
        new Dish("回锅肉", "川菜", "晚餐", 2, 30, 460, "重口,下饭"),
        new Dish("白切鸡", "粤菜", "晚餐", 0, 45, 300, "清淡"),
        new Dish("清蒸鲈鱼", "粤菜", "晚餐", 0, 30, 220, "清淡,高蛋白"),
        new Dish("广式煲仔饭", "粤菜", "午餐", 0, 40, 550, "顶饱"),
        new Dish("虾饺", "粤菜", "早餐", 0, 30, 200, "点心"),
        new Dish("皮蛋瘦肉粥", "粤菜", "早餐", 0, 30, 180, "清淡,暖胃"),
        new Dish("兰州牛肉面", "面食", "午餐", 1, 15, 480, "快手,顶饱,汤"),
        new Dish("炸酱面", "面食", "午餐", 0, 20, 520, "顶饱"),
        new Dish("重庆小面", "面食", "早餐", 3, 15, 450, "麻辣,快手"),
        new Dish("阳春面", "面食", "早餐", 0, 10, 300, "清淡,快手"),
        new Dish("刀削面", "面食", "午餐", 1, 25, 500, "顶饱"),
        new Dish("扬州炒饭", "米饭", "午餐", 0, 15, 520, "快手,顶饱"),
        new Dish("黄焖鸡米饭", "米饭", "午餐", 1, 25, 600, "顶饱"),
        new Dish("猪脚饭", "米饭", "午餐", 0, 20, 650, "顶饱,油腻"),
        new Dish("咖喱牛肉饭", "米饭", "晚餐", 1, 35, 580, "顶饱"),
        new Dish("石锅拌饭", "米饭", "晚餐", 1, 25, 550, "顶饱"),
        new Dish("三鲜水饺", "饺子", "晚餐", 0, 30, 420, "顶饱"),
        new Dish("韭菜盒子", "饺子", "早餐", 0, 25, 380, "顶饱"),
        new Dish("小笼包", "小吃", "早餐", 0, 20, 300, "点心"),
        new Dish("煎饼果子", "小吃", "早餐", 1, 10, 420, "快手"),
        new Dish("肉夹馍", "小吃", "午餐", 1, 15, 450, "快手,顶饱"),
        new Dish("凉皮", "小吃", "午餐", 2, 10, 350, "清爽,快手"),
        new Dish("番茄鸡蛋面", "面食", "早餐", 0, 15, 380, "快手,清淡"),
        new Dish("麻辣香锅", "川菜", "晚餐", 3, 35, 620, "麻辣,重口"),
        new Dish("酸菜鱼", "川菜", "晚餐", 2, 40, 420, "酸辣,硬菜"),
        new Dish("毛血旺", "川菜", "晚餐", 3, 40, 550, "麻辣,重口"),
        new Dish("小炒黄牛肉", "湘菜", "晚餐", 3, 25, 430, "香辣,下饭"),
        new Dish("剁椒鱼头", "湘菜", "晚餐", 3, 35, 400, "香辣,硬菜"),
        new Dish("湖南米粉", "湘菜", "早餐", 2, 15, 450, "快手,汤"),
        new Dish("干锅花菜", "湘菜", "晚餐", 2, 25, 320, "下饭"),
        new Dish("地三鲜", "东北菜", "午餐", 0, 25, 350, "下饭,素"),
        new Dish("锅包肉", "东北菜", "晚餐", 0, 30, 480, "酸甜,硬菜"),
        new Dish("东北乱炖", "东北菜", "晚餐", 0, 50, 400, "炖菜,顶饱"),
        new Dish("猪肉炖粉条", "东北菜", "晚餐", 0, 45, 520, "炖菜,顶饱"),
        new Dish("酸菜白肉", "东北菜", "晚餐", 0, 50, 460, "炖菜,解腻"),
        new Dish("酸辣土豆丝", "家常", "午餐", 1, 15, 220, "快手,下饭,素"),
        new Dish("干煸豆角", "家常", "午餐", 1, 20, 260, "下饭,素"),
        new Dish("蒜蓉西兰花", "家常", "晚餐", 0, 15, 150, "清淡,素,健康"),
        new Dish("上汤娃娃菜", "粤菜", "晚餐", 0, 20, 130, "清淡,素,健康"),
        new Dish("清炒时蔬", "家常", "午餐", 0, 10, 120, "清淡,素,健康,快手"),
        new Dish("凉拌黄瓜", "凉菜", "午餐", 1, 5, 80, "清爽,快手,低卡"),
        new Dish("口水鸡", "川菜", "晚餐", 3, 25, 340, "麻辣,凉菜"),
        new Dish("夫妻肺片", "川菜", "晚餐", 3, 20, 320, "麻辣,凉菜"),
        new Dish("麻辣烫", "小吃", "晚餐", 3, 20, 500, "麻辣,自由搭配"),
        new Dish("关东煮", "小吃", "夜宵", 0, 10, 250, "清淡,快手"),
        new Dish("烤冷面", "小吃", "夜宵", 1, 10, 380, "快手"),
        new Dish("炸鸡啤酒", "小吃", "夜宵", 1, 25, 700, "油腻,解压"),
        new Dish("酸辣粉", "小吃", "夜宵", 3, 15, 420, "麻辣,汤"),
        new Dish("韩式炸酱面", "韩餐", "午餐", 0, 25, 550, "甜口,顶饱"),
        new Dish("部队锅", "韩餐", "晚餐", 2, 35, 600, "汤,重口"),
        new Dish("石锅豆腐汤", "韩餐", "晚餐", 2, 25, 300, "汤,暖胃"),
        new Dish("冬阴功汤", "东南亚", "晚餐", 3, 30, 280, "酸辣,汤"),
        new Dish("海南鸡饭", "东南亚", "午餐", 0, 35, 520, "清淡,顶饱"),
        new Dish("日式咖喱饭", "日料", "晚餐", 1, 30, 560, "顶饱"),
        new Dish("豚骨拉面", "日料", "晚餐", 0, 20, 650, "汤,顶饱"),
        new Dish("寿司拼盘", "日料", "午餐", 0, 20, 380, "清淡,低卡"),
        new Dish("牛肉火锅", "火锅", "晚餐", 2, 60, 800, "聚餐,硬菜"),
        new Dish("串串香", "火锅", "夜宵", 3, 50, 700, "聚餐,麻辣"),
        new Dish("椰子鸡火锅", "火锅", "晚餐", 0, 50, 400, "清淡,聚餐"),
        new Dish("芝士焗饭", "西餐", "午餐", 0, 30, 620, "顶饱,油腻"),
        new Dish("黑椒牛柳意面", "西餐", "晚餐", 1, 30, 580, "顶饱"),
        new Dish("凯撒沙拉", "西餐", "午餐", 0, 10, 250, "低卡,健康,快手"),
        new Dish("汉堡薯条", "西餐", "午餐", 0, 15, 750, "油腻,快手"),
        new Dish("三明治", "西餐", "早餐", 0, 5, 300, "快手,低卡"),
        new Dish("煎蛋吐司", "西餐", "早餐", 0, 8, 280, "快手,简单"),
        new Dish("燕麦牛奶", "健康餐", "早餐", 0, 5, 220, "健康,低卡,快手"),
        new Dish("鸡胸肉沙拉", "健康餐", "午餐", 0, 15, 320, "健康,低卡,高蛋白"),
        new Dish("水煮西兰花鸡胸", "健康餐", "晚餐", 0, 20, 300, "健康,低卡,高蛋白"),
        new Dish("杂粮饭配时蔬", "健康餐", "晚餐", 0, 25, 350, "健康,素"),
        new Dish("番茄牛腩汤", "汤羹", "晚餐", 0, 60, 380, "汤,暖胃,炖菜"),
        new Dish("紫菜蛋花汤", "汤羹", "午餐", 0, 10, 90, "汤,清淡,快手"),
        new Dish("玉米排骨汤", "汤羹", "晚餐", 0, 70, 350, "汤,暖胃,炖菜"),
        new Dish("疙瘩汤", "汤羹", "早餐", 0, 20, 300, "暖胃,顶饱")
    );

    static final Map<String, Dish> DISH_MAP = new LinkedHashMap<>();
    static {
        for (Dish d : DISHES) DISH_MAP.put(d.name(), d);
    }

    // ======================= 数据存储 =======================

    static class DataStore {
        final Map<String, DishStat> stats = new LinkedHashMap<>();
        final List<String> history = new ArrayList<>();   // 每行: 日期|菜名|评分(0=未评分)
        final RecParams params = new RecParams();

        Path dataDir;

        DataStore(Path dataDir) {
            this.dataDir = dataDir;
            for (Dish d : DISHES) stats.put(d.name(), new DishStat());
        }

        DishStat stat(String dish) {
            return stats.computeIfAbsent(dish, k -> new DishStat());
        }

        /** 返回最近 days 天内吃过、应当被排除的菜名集合（与引擎的硬性排除规则一致，可选调用） */
        Set<String> recentEatenSet(int days, LocalDate today) {
            Set<String> set = new LinkedHashSet<>();
            for (Map.Entry<String, DishStat> e : stats.entrySet()) {
                LocalDate last = e.getValue().lastEaten;
                if (last == null) continue;                       // 从没吃过，不排除
                long gap = java.time.temporal.ChronoUnit.DAYS.between(last, today);
                if (gap < days) set.add(e.getKey());              // 还没满 days 天，排除
            }
            return set;
        }

        /** 记录吃了一道菜 */
        void recordEat(String dish, int rating, String mealType, LocalDate date) {
            DishStat s = stat(dish);
            s.eatCount++;
            s.lastEaten = date;
            if (rating > 0) {
                // 多次评分取平均，避免一次打分永久锁死
                s.stars = s.stars == 0 ? rating : Math.round((s.stars + rating) / 2.0f);
            }
            history.add(date + "|" + dish + "|" + rating + "|" + mealType);
        }

        void recordFeedback(String dish, boolean good) {
            DishStat s = stat(dish);
            if (good) s.goodFeedback++; else s.badFeedback++;
        }

        // ---------- 持久化（制表符分隔的纯文本，够用且好读） ----------

        void save() throws IOException {
            Files.createDirectories(dataDir);
            StringBuilder sb = new StringBuilder("# dish\tstars\teatCount\tlastEaten\tgood\tbad\n");
            for (Map.Entry<String, DishStat> e : stats.entrySet()) {
                DishStat s = e.getValue();
                sb.append(e.getKey()).append('\t')
                  .append(s.stars).append('\t')
                  .append(s.eatCount).append('\t')
                  .append(s.lastEaten == null ? "-" : s.lastEaten).append('\t')
                  .append(s.goodFeedback).append('\t')
                  .append(s.badFeedback).append('\n');
            }
            Files.writeString(dataDir.resolve("stats.tsv"), sb.toString(), StandardCharsets.UTF_8);

            StringBuilder hb = new StringBuilder("# date|dish|rating|mealType\n");
            for (String h : history) hb.append(h).append('\n');
            Files.writeString(dataDir.resolve("history.txt"), hb.toString(), StandardCharsets.UTF_8);

            String p = params.alpha + "\t" + params.beta + "\t" + params.lambda + "\t"
                     + params.gamma + "\t" + params.noiseAmp + "\t" + params.excludeRecentDays
                     + "\t" + params.minPool + "\n";
            Files.writeString(dataDir.resolve("params.tsv"), p, StandardCharsets.UTF_8);
        }

        void load() throws IOException {
            Path sf = dataDir.resolve("stats.tsv");
            if (Files.exists(sf)) {
                for (String line : Files.readAllLines(sf, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] t = line.split("\t", -1);
                    if (t.length < 6) continue;
                    DishStat s = stat(t[0]);
                    s.stars = parseInt(t[1], 0);
                    s.eatCount = parseInt(t[2], 0);
                    s.lastEaten = "-".equals(t[3]) || t[3].isBlank() ? null : LocalDate.parse(t[3]);
                    s.goodFeedback = parseInt(t[4], 0);
                    s.badFeedback = parseInt(t[5], 0);
                }
            }
            Path hf = dataDir.resolve("history.txt");
            if (Files.exists(hf)) {
                for (String line : Files.readAllLines(hf, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    history.add(line);
                }
            }
            Path pf = dataDir.resolve("params.tsv");
            if (Files.exists(pf)) {
                String content = Files.readString(pf, StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    String[] t = content.split("\t");
                    if (t.length >= 6) {
                        params.alpha = parseDouble(t[0], params.alpha);
                        params.beta = parseDouble(t[1], params.beta);
                        params.lambda = parseDouble(t[2], params.lambda);
                        params.gamma = parseDouble(t[3], params.gamma);
                        params.noiseAmp = parseDouble(t[4], params.noiseAmp);
                        params.excludeRecentDays = parseInt(t[5], params.excludeRecentDays);
                        // t[6] 是后加的 minPool，老配置文件没有这个字段，缺失就用默认值
                        if (t.length >= 7) params.minPool = parseInt(t[6], params.minPool);
                    }
                }
            }
        }

        void clearAll() {
            for (DishStat s : stats.values()) {
                s.stars = 0; s.eatCount = 0; s.lastEaten = null;
                s.goodFeedback = 0; s.badFeedback = 0;
            }
            history.clear();
        }
    }

    // ======================= 推荐引擎 =======================

    static class Recommender {
        final DataStore store;
        final Random random = new Random();

        Recommender(DataStore store, long seed) {
            this.store = store;
            this.random.setSeed(seed);
        }

        /**
         * 给一道菜打分。
         * excludeDays 是本次生效的排除天数（可能已被 recommend() 自动放宽）。
         * 返回 null 表示这道菜被硬性排除（刚吃过 / 不符合餐段）。
         */
        ScoreDetail score(Dish dish, String mealType, String weather, LocalDate today, int excludeDays) {
            RecParams p = store.params;
            DishStat s = store.stat(dish.name());
            long days = s.lastEaten == null
                    ? 999
                    : java.time.temporal.ChronoUnit.DAYS.between(s.lastEaten, today);

            // 餐段过滤：早餐档不推硬菜
            if (mealType != null && !mealType.isBlank() && !dish.mealType().equals(mealType)) {
                return null;
            }
            // 刚吃过的硬性排除
            if (!p.includeRecent && s.lastEaten != null && days < excludeDays) {
                return null;
            }

            // ---- 1. 偏好分 P ----
            double base = s.stars == 0 ? 0.60 : s.stars / 5.0;          // 没打过分给中性值
            double freq = Math.min(1.0, s.eatCount / 10.0) * 0.10;      // 常吃加分，最多 +0.10
            double fb = s.avgFeedback();
            double fbDelta = (s.goodFeedback + s.badFeedback) == 0 ? 0 : (fb - 0.5) * 0.40;
            double preference = clamp01(base + freq + fbDelta);

            // ---- 2. 时间分 R = 1 - exp(-t / lambda) ----
            double lambda = lambdaFor(dish, p.lambda);
            double novelty = days >= 999 ? 1.0 : 1.0 - Math.exp(-(double) days / lambda);

            // ---- 3. 场景调整 ----
            double scene = sceneAdjust(dish, weather, s, today);

            // ---- 4. 惩罚分 ----
            double penalty = 0.0;
            if (s.lastEaten != null && days < 7) {
                penalty += 0.15 * (7 - days) / 7.0;                     // 一周内吃过，轻度降权
            }
            if (dish.spicy() >= 3 && recentSpicyCount(today, 3) >= 2) {
                penalty += 0.25;                                        // 连着吃辣，劝退
            }
            if (dish.tagsOrDefault().contains("油腻") && recentHeavyCount(today, 3) >= 2) {
                penalty += 0.20;
            }
            // 惩罚项属于"时间维度"的一部分，必须跟着 beta 一起缩放。
            // 否则把 beta 调成 0 做消融实验时，惩罚还在偷偷生效，对照组就不干净了。
            penalty *= p.beta;

            // ---- 5. 探索噪声 ----
            double noise = (random.nextDouble() * 2 - 1) * p.noiseAmp;

            double total = p.alpha * preference
                         + p.beta * novelty
                         + p.gamma * scene
                         - penalty
                         + noise;

            ScoreDetail d = new ScoreDetail();
            d.dish = dish.name();
            d.preference = round4(preference);
            d.novelty = round4(novelty);
            d.scene = round4(scene);
            d.penalty = round4(penalty);
            d.noise = round4(noise);
            d.total = round4(total);
            d.daysSince = days >= 999 ? -1 : days;
            d.explain = explain(s, dish, days, novelty, scene, penalty);
            return d;
        }

        /** 不同菜系的"想念速度"不同：清淡的可以天天吃，重口的容易腻 */
        static double lambdaFor(Dish dish, double baseLambda) {
            return switch (dish.cuisine()) {
                case "健康餐", "汤羹", "家常" -> baseLambda * 1.6;
                case "粤菜", "日料" -> baseLambda * 1.2;
                case "火锅", "川菜", "湘菜" -> baseLambda * 0.7;
                default -> baseLambda;
            };
        }

        double sceneAdjust(Dish dish, String weather, DishStat s, LocalDate today) {
            double scene = 0.0;
            if (weather != null) {
                switch (weather) {
                    case "雨", "冷" -> {
                        if (dish.tagsOrDefault().contains("汤") || dish.tagsOrDefault().contains("暖胃")
                                || dish.tagsOrDefault().contains("炖菜")) scene += 0.30;
                        if (dish.tagsOrDefault().contains("清爽") || dish.tagsOrDefault().contains("低卡")) scene -= 0.10;
                    }
                    case "热" -> {
                        if (dish.tagsOrDefault().contains("清爽") || dish.tagsOrDefault().contains("凉菜")) scene += 0.30;
                        if (dish.tagsOrDefault().contains("炖菜") || dish.spicy() >= 3) scene -= 0.10;
                    }
                    default -> { /* 晴/阴 不做调整 */ }
                }
            }
            return scene;
        }

        int recentSpicyCount(LocalDate today, int days) {
            int c = 0;
            for (String h : store.history) {
                String[] t = h.split("\\|");
                if (t.length < 2) continue;
                LocalDate d = tryDate(t[0]);
                if (d == null || d.isBefore(today.minusDays(days))) continue;
                Dish dish = DISH_MAP.get(t[1]);
                if (dish != null && dish.spicy() >= 3) c++;
            }
            return c;
        }

        int recentHeavyCount(LocalDate today, int days) {
            int c = 0;
            for (String h : store.history) {
                String[] t = h.split("\\|");
                if (t.length < 2) continue;
                LocalDate d = tryDate(t[0]);
                if (d == null || d.isBefore(today.minusDays(days))) continue;
                Dish dish = DISH_MAP.get(t[1]);
                if (dish != null && dish.tagsOrDefault().contains("油腻")) c++;
            }
            return c;
        }

        static String explain(DishStat s, Dish dish, long days, double novelty, double scene, double penalty) {
            List<String> parts = new ArrayList<>();
            if (s.eatCount == 0) {
                parts.add("还没吃过这道菜");
            } else if (days >= 999) {
                parts.add("很久没吃");
            } else if (days == 0) {
                parts.add("今天刚吃过");
            } else {
                parts.add(days + " 天没吃了");
            }
            if (s.stars > 0) parts.add("你的评分 " + s.stars + " 星");
            if (novelty > 0.8) parts.add("新鲜度很高");
            if (scene > 0.05) parts.add("很适合当前天气");
            if (penalty > 0.05) parts.add("最近吃得多，适当降权");
            if (s.badFeedback > 0 && s.avgFeedback() < 0.5) parts.add("你以前标记过吃腻了");
            return String.join("，", parts);
        }

        /** 推荐 topN，会自动放宽排除天数保证候选池不为空 */
        List<ScoreDetail> recommend(String mealType, String weather, int topN, LocalDate today) {
            // 「吃遍了怎么办」：如果最近 N 天吃过的都被排除，候选池会空掉。
            // 这里逐天放宽排除窗口，直到候选数够用或彻底不放宽为止。
            int effectiveDays = store.params.excludeRecentDays;
            List<ScoreDetail> list = scoreAll(mealType, weather, today, effectiveDays);
            int minPool = Math.max(store.params.minPool, Math.min(3, topN));
            while (list.size() < minPool && effectiveDays > 0) {
                effectiveDays--;
                list = scoreAll(mealType, weather, today, effectiveDays);
            }
            lastRelaxedDays = effectiveDays;
            list.sort((a, b) -> Double.compare(b.total, a.total));
            return list.size() > topN ? new ArrayList<>(list.subList(0, topN)) : list;
        }

        /** 当前生效的排除天数（可能因为池子不够被自动放宽） */
        int lastRelaxedDays = -1;

        private List<ScoreDetail> scoreAll(String mealType, String weather, LocalDate today, int excludeDays) {
            List<ScoreDetail> list = new ArrayList<>();
            for (Dish d : DISHES) {
                ScoreDetail sd = score(d, mealType, weather, today, excludeDays);
                if (sd != null) list.add(sd);
            }
            return list;
        }
    }

    // ======================= 迷你 JSON 工具 =======================

    static String json(Object o) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static void writeJson(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String s) { sb.append('"').append(esc(s)).append('"'); return; }
        if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(esc(String.valueOf(e.getKey()))).append("\":");
                writeJson(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object v : it) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, v);
            }
            sb.append(']');
            return;
        }
        if (o instanceof ScoreDetail d) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dish", d.dish);
            m.put("preference", d.preference);
            m.put("novelty", d.novelty);
            m.put("scene", d.scene);
            m.put("penalty", d.penalty);
            m.put("noise", d.noise);
            m.put("total", d.total);
            m.put("daysSince", d.daysSince);
            m.put("explain", d.explain);
            writeJson(sb, m);
            return;
        }
        sb.append('"').append(esc(String.valueOf(o))).append('"');
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** 极简 JSON 解析：只处理扁平的 {"key":value} 结构，够 demo 用 */
    static Map<String, String> parseFlatJson(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null) return map;
        body = body.trim();
        if (body.startsWith("{")) body = body.substring(1);
        if (body.endsWith("}")) body = body.substring(0, body.length() - 1);
        for (String part : body.split(",")) {
            int idx = part.indexOf(':');
            if (idx < 0) continue;
            String k = part.substring(0, idx).trim().replace("\"", "");
            String v = part.substring(idx + 1).trim().replace("\"", "");
            if (!k.isEmpty()) map.put(k, v);
        }
        return map;
    }

    // ======================= HTTP 服务 =======================

    static DataStore store;
    static Recommender recommender;
    static Path webDir;

    public static void main(String[] args) throws IOException {
        int port = 8080;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }

        Path base = Paths.get("").toAbsolutePath();
        // 兼容从项目根目录或 out 目录启动
        Path candidate = Files.exists(base.resolve("web/index.html")) ? base : base.getParent();
        webDir = candidate.resolve("web");
        Path dataDir = candidate.resolve("data");

        store = new DataStore(dataDir);
        store.load();
        recommender = new Recommender(store, 20260101L);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", SparkServer::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        long rated = store.stats.values().stream().filter(s -> s.stars > 0).count();
        System.out.println("========================================");
        System.out.println("  每日饮食推荐系统 已启动");
        System.out.println("  地址: http://localhost:" + port);
        System.out.println("  菜品: " + DISHES.size() + " 道 | 已评分: " + rated + " 道 | 历史记录: " + store.history.size() + " 条");
        System.out.println("  数据目录: " + dataDir);
        System.out.println("  按 Ctrl+C 停止");
        System.out.println("========================================");
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            if ("/api/recommend".equals(path)) {
                handleRecommend(ex, query);
            } else if ("/api/eat".equals(path)) {
                handleEat(ex);
            } else if ("/api/rate".equals(path)) {
                handleRate(ex);
            } else if ("/api/feedback".equals(path)) {
                handleFeedback(ex);
            } else if ("/api/stats".equals(path)) {
                handleStats(ex);
            } else if ("/api/params".equals(path)) {
                handleParams(ex);
            } else if ("/api/reset".equals(path)) {
                store.clearAll();
                store.save();
                sendJson(ex, 200, "{\"ok\":true}");
            } else if ("/api/dishes".equals(path)) {
                sendJson(ex, 200, json(dishListJson()));
            } else if ("/api/history".equals(path)) {
                sendJson(ex, 200, json(store.history));
            } else {
                serveStatic(ex, path);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, "{\"error\":\"" + esc(String.valueOf(e.getMessage())) + "\"}");
        } finally {
            ex.close();
        }
    }

    // ---------- 各接口 ----------

    static void handleRecommend(HttpExchange ex, String query) throws IOException {
        Map<String, String> q = parseQuery(query);
        String meal = q.getOrDefault("meal", defaultMealByHour());
        String weather = q.getOrDefault("weather", "晴");
        int topN = (int) parseDouble(q.getOrDefault("topN", "6"), 6);

        LocalDate today = LocalDate.now();
        // 一次算够 15 条，推荐卡片用的 topN 从这里切，保证两次调用参数一致
        List<ScoreDetail> all = recommender.recommend(meal, weather, 15, today);
        List<ScoreDetail> top = all.size() > topN ? new ArrayList<>(all.subList(0, topN)) : all;

        int relaxedTo = recommender.lastRelaxedDays;
        int configured = store.params.excludeRecentDays;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mealType", meal);
        resp.put("weather", weather);
        resp.put("today", today.toString());
        resp.put("recommendations", top);
        resp.put("candidateCount", all.size());
        resp.put("excludedRecent", relaxedTo >= 0 ? relaxedTo : configured);
        resp.put("configuredExcludeDays", configured);
        resp.put("relaxed", relaxedTo >= 0 && relaxedTo < configured);
        resp.put("poolNote", buildPoolNote(relaxedTo, configured, all.size()));

        // 附带全量排名（前 15），方便"为什么不是它"
        resp.put("top15", all);

        sendJson(ex, 200, json(resp));
    }

    static void handleEat(HttpExchange ex) throws IOException {
        Map<String, String> body = parseFlatJson(readBody(ex));
        String dish = body.get("dish");
        if (dish == null || !DISH_MAP.containsKey(dish)) {
            sendJson(ex, 400, "{\"error\":\"unknown dish\"}");
            return;
        }
        int rating = (int) parseDouble(body.getOrDefault("rating", "0"), 0);
        String meal = body.getOrDefault("meal", defaultMealByHour());

        // 支持 ?date=2026-10-05 回填历史记录，方便观察时间衰减（不传则记今天）
        String dateStr = parseQuery(ex.getRequestURI().getRawQuery()).get("date");
        LocalDate when = tryDate(dateStr == null ? "" : dateStr);
        if (when == null) when = LocalDate.now();

        store.recordEat(dish, rating, meal, when);
        store.save();
        sendJson(ex, 200, "{\"ok\":true,\"dish\":\"" + esc(dish) + "\",\"date\":\"" + when + "\"}");
    }

    static void handleRate(HttpExchange ex) throws IOException {
        Map<String, String> body = parseFlatJson(readBody(ex));
        String dish = body.get("dish");
        if (dish == null || !DISH_MAP.containsKey(dish)) {
            sendJson(ex, 400, "{\"error\":\"unknown dish\"}");
            return;
        }
        int stars = (int) clamp((int) parseDouble(body.getOrDefault("stars", "0"), 0), 0, 5);
        DishStat s = store.stat(dish);
        s.stars = stars;
        store.save();
        sendJson(ex, 200, "{\"ok\":true}");
    }

    static void handleFeedback(HttpExchange ex) throws IOException {
        Map<String, String> body = parseFlatJson(readBody(ex));
        String dish = body.get("dish");
        boolean good = "true".equalsIgnoreCase(body.getOrDefault("good", "true"));
        if (dish == null || !DISH_MAP.containsKey(dish)) {
            sendJson(ex, 400, "{\"error\":\"unknown dish\"}");
            return;
        }
        store.recordFeedback(dish, good);
        store.save();
        sendJson(ex, 200, "{\"ok\":true}");
    }

    static void handleStats(HttpExchange ex) throws IOException {
        List<Map<String, Object>> dishStats = dishListJson();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", DISHES.size());
        resp.put("rated", dishStats.stream().filter(m -> ((Number) m.get("stars")).intValue() > 0).count());
        resp.put("eaten", store.stats.values().stream().filter(s -> s.eatCount > 0).count());
        resp.put("historyCount", store.history.size());
        resp.put("params", paramsJson(store.params));
        resp.put("dishes", dishStats);
        resp.put("history", store.history);
        sendJson(ex, 200, json(resp));
    }

    static void handleParams(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 200, json(paramsJson(store.params)));
            return;
        }
        Map<String, String> body = parseFlatJson(readBody(ex));
        RecParams p = store.params;
        p.alpha = parseDouble(body.getOrDefault("alpha", String.valueOf(p.alpha)), p.alpha);
        p.beta = parseDouble(body.getOrDefault("beta", String.valueOf(p.beta)), p.beta);
        p.lambda = parseDouble(body.getOrDefault("lambda", String.valueOf(p.lambda)), p.lambda);
        p.gamma = parseDouble(body.getOrDefault("gamma", String.valueOf(p.gamma)), p.gamma);
        p.noiseAmp = parseDouble(body.getOrDefault("noiseAmp", String.valueOf(p.noiseAmp)), p.noiseAmp);
        p.excludeRecentDays = (int) parseDouble(body.getOrDefault("excludeRecentDays",
                String.valueOf(p.excludeRecentDays)), p.excludeRecentDays);
        p.minPool = (int) parseDouble(body.getOrDefault("minPool",
                String.valueOf(p.minPool)), p.minPool);
        p.includeRecent = Boolean.parseBoolean(body.getOrDefault("includeRecent",
                String.valueOf(p.includeRecent)));
        store.save();
        sendJson(ex, 200, json(paramsJson(p)));
    }

    /** 候选池被自动放宽时，给前端一句人话解释 */
    static String buildPoolNote(int relaxedTo, int configured, int candidateCount) {
        if (relaxedTo < 0 || relaxedTo >= configured) {
            return "候选池充足（" + candidateCount + " 道），按设置的排除 " + configured + " 天";
        }
        if (relaxedTo == 0) {
            return "你最近吃的太多了，排除规则已完全放开 —— 现在只按喜好排序";
        }
        return "候选不够，" + configured + " 天已自动放宽到 " + relaxedTo + " 天（候选 " + candidateCount + " 道）";
    }

    static Map<String, Object> paramsJson(RecParams p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alpha", p.alpha);
        m.put("beta", p.beta);
        m.put("lambda", p.lambda);
        m.put("gamma", p.gamma);
        m.put("noiseAmp", p.noiseAmp);
        m.put("excludeRecentDays", p.excludeRecentDays);
        m.put("minPool", p.minPool);
        m.put("includeRecent", p.includeRecent);
        return m;
    }

    static List<Map<String, Object>> dishListJson() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Dish d : DISHES) {
            DishStat s = store.stat(d.name());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name());
            m.put("cuisine", d.cuisine());
            m.put("mealType", d.mealType());
            m.put("spicy", d.spicy());
            m.put("minutes", d.minutes());
            m.put("calories", d.calories());
            m.put("tags", d.tagsOrDefault());
            m.put("stars", s.stars);
            m.put("eatCount", s.eatCount);
            m.put("lastEaten", s.lastEaten == null ? "" : s.lastEaten.toString());
            m.put("good", s.goodFeedback);
            m.put("bad", s.badFeedback);
            list.add(m);
        }
        return list;
    }

    // ---------- 静态文件 ----------

    static void serveStatic(HttpExchange ex, String path) throws IOException {
        if ("/".equals(path) || path.isEmpty()) path = "/index.html";
        Path file = webDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webDir) || !Files.exists(file) || Files.isDirectory(file)) {
            byte[] msg = "404 Not Found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        String ct = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ---------- 小工具 ----------

    static void sendJson(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            try {
                map.put(java.net.URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        return map;
    }

    static String defaultMealByHour() {
        int h = LocalDateTime.now().getHour();
        if (h < 10) return "早餐";
        if (h < 15) return "午餐";
        if (h < 21) return "晚餐";
        return "夜宵";
    }

    static LocalDate tryDate(String s) {
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
