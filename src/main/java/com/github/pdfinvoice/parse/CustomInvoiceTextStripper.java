package com.github.pdfinvoice.parse;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pdfbox.contentstream.operator.color.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Rectangle2D;
import java.awt.geom.RectangularShape;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 根据关键字进行分区块解析
 */
@Getter
@Setter
@Slf4j
public class CustomInvoiceTextStripper extends PDFTextStripperByArea {

    // 探测红色文字
    private boolean detachColorText;

    private BiPredicate<TextPosition, PDColor> colorPredicate;
    // 红色文字集合
    private final List<TextPosition> textPositions = new ArrayList<>();

    // 下列文字集解析
    private boolean parsedPosition;
    // 水平文字
    private LinkedHashMap<String, List<TextPosition>> horizonText = new LinkedHashMap<>();
    // maybe垂直文字
    private LinkedHashMap<String, List<TextPosition>> orVerticalText = new LinkedHashMap<>();


    // 异形字处理
    private static final Map<String, String> REPLACEMENTS = new HashMap<>();
    private static final List<Pattern> PATTERNS;

    // 数据
    private Invoice invoice;

    static {
        REPLACEMENTS.put("⽅", "方");
        REPLACEMENTS.put("⼦", "子");
        REPLACEMENTS.put("⼈", "人");
        REPLACEMENTS.put("⾦", "金");

        PATTERNS = Stream.of("[^\\x20]+名称", "规格型号", "单[^\u4e00-\u9fa5]*位", "数[^\u4e00-\u9fa5]*量", "单[^\u4e00-\u9fa5]*价", "[金⾦][^\u4e00-\u9fa5]*额", "税[^\u4e00-\u9fa5]*率(\\/征收率)?", "税[^\u4e00-\u9fa5]*额").map(e -> Pattern.compile(e, Pattern.DOTALL)).collect(Collectors.toList());
    }

    public CustomInvoiceTextStripper(PDDocument document) throws Exception {

        // 颜色处理
        addOperator(new SetStrokingColorSpace(this));
        addOperator(new SetNonStrokingColorSpace(this));
        addOperator(new SetStrokingDeviceCMYKColor(this));
        addOperator(new SetNonStrokingDeviceCMYKColor(this));
        addOperator(new SetNonStrokingDeviceRGBColor(this));
        addOperator(new SetStrokingDeviceRGBColor(this));
        addOperator(new SetNonStrokingDeviceGrayColor(this));
        addOperator(new SetStrokingDeviceGrayColor(this));
        addOperator(new SetStrokingColor(this));
        addOperator(new SetStrokingColorN(this));
        addOperator(new SetNonStrokingColor(this));
        addOperator(new SetNonStrokingColorN(this));
        setSortByPosition(true);

        orVerticalText.put("下载次数", new ArrayList<>());
        orVerticalText.put("备注", new ArrayList<>());
        orVerticalText.put("购买方信息", new ArrayList<>());
        orVerticalText.put("销售方信息", new ArrayList<>());
        orVerticalText.put("密码区", new ArrayList<>());

        horizonText.put("发票号码", new ArrayList<>());
        horizonText.put("价税合计", new ArrayList<>());
        horizonText.put("合计", new ArrayList<>());
        horizonText.put("开票人", new ArrayList<>());


        this.parse(document);
    }


    /**
     * 增加二维矩阵高度
     *
     * @param rectangle2D
     * @param y
     * @return
     */
    private Rectangle2D padding(Rectangle2D rectangle2D, double y) {
        return new Rectangle2D.Double(rectangle2D.getX(), rectangle2D.getY() - y, rectangle2D.getWidth(), rectangle2D.getHeight() + y * 2);
    }

    /**
     * 文字组合
     *
     * @param key           查找文字
     * @param textPositions 文档中所有关联key的字集合
     * @param predicate
     * @return
     */
    private List<TextPosition> detachText(String key, List<TextPosition> textPositions, BiPredicate<String, List<TextPosition>> predicate) {
        float pt = 1F;
        String c = Objects.toString(key.charAt(0));

        return textPositions.stream().filter(e -> Objects.equals(e.getUnicode(), c)).map(e -> {
            List<TextPosition> list = new ArrayList<>();
            list.add(e);
            // 备注 两字纵向差值 30多, 防止距离远的纵向字被包含
            float fpt = 40F;
            return textPositions.stream().filter(t -> !Objects.equals(e, t)).reduce(list, (l, t) -> {
                TextPosition cur = l.get(l.size() - 1);
                float absX = Math.abs(t.getX() - cur.getX());
                float absY = t.getY() - cur.getY();
                if (absX < pt && absY > 0 && absY < fpt) {
                    l.add(t);
                }
                return l;
            }, (l1, l2) -> l1);
        }).filter(e -> e.size() > 1).max(Comparator.comparingInt(List::size)).filter(e -> predicate.test(key, e)).orElseGet(Collections::emptyList);

    }

    public void parse(PDDocument document) throws Exception {

        PDPage page = document.getPage(0);

        addRegion("page", new Rectangle2D.Double(0, 0, page.getCropBox().getWidth(), page.getCropBox().getHeight()));
        extractRegions(page);

        String region = getTextForRegion("page");

        removeRegion("page");

        if (StringUtils.isBlank(region)) {
            throw new IllegalArgumentException("发票首页内容解析为空，请确认文档正确性");
        }

        // 位置解析完毕标记
        parsedPosition = true;

        // 解析颜色
        detachColorText = true;


        Map<String, List<TextPosition>> verticalText = orVerticalText.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> detachText(e.getKey(), e.getValue(), (k, v) -> Objects.equals(k.length(), v.size()) || (k.contains("方") && v.size() == 3))));

        Map<String, Rectangle2D> verticalCollect = verticalText.entrySet().stream().filter(e -> CollectionUtils.isNotEmpty(e.getValue())).collect(Collectors.toMap(Map.Entry::getKey, e -> getRectangle2D(e.getValue())));

        // 下载次数修正长度
        Double side = Optional.ofNullable(verticalCollect.remove("下载次数")).map(RectangularShape::getX).orElse(0D);
        double width = Objects.equals(0D, side) ? Math.max(page.getCropBox().getWidth(), page.getCropBox().getHeight()) : side;


        Map<String, Float> textHeight = Stream.of(verticalText.entrySet(), horizonText.entrySet()).flatMap(Set::stream).filter(e -> CollectionUtils.isNotEmpty(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get(0).getYScale()));

        Map<String, Rectangle2D> horizonCollect = horizonText.entrySet().stream().filter(e -> CollectionUtils.isNotEmpty(e.getValue())).collect(Collectors.toMap(Map.Entry::getKey, e -> getRectangle2D(e.getValue())));
        Rectangle2D fphm = Objects.requireNonNull(horizonCollect.remove("发票号码"), "发票号码区块不存在");


        Map<String, Rectangle2D> map = verticalCollect.entrySet().stream()
                .filter(e -> Objects.nonNull(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    Rectangle2D v = entry.getValue();
                    Rectangle2D rectangle2D = verticalCollect.values().stream().filter(Objects::nonNull).filter(e -> {
                        boolean b = !Objects.equals(e, v);
                        double c = v.getY() + v.getHeight() / 2;
                        boolean conflict = v.getX() < e.getX() && c >= e.getY() && c <= e.getY() + e.getHeight();
                        return b && conflict;
                    }).min(Comparator.comparingDouble(RectangularShape::getX)).map(e -> {
                        double v1 = e.getX() - 1;
                        return new Rectangle2D.Double(v.getX() + v.getWidth(), v.getY(), v1 - (v.getX() + v.getWidth()), v.getHeight());
                    }).orElseGet(() -> new Rectangle2D.Double(v.getX() + v.getWidth(), v.getY(), width - (v.getX() + v.getWidth()), v.getHeight()));
                    return padding(rectangle2D, textHeight.get(entry.getKey()) * 0.9);
                }));

        horizonCollect.forEach((k, v) -> {
            Rectangle2D padding = padding(v, textHeight.get(k) * 0.5);
            map.put(k, new Rectangle2D.Double(0, padding.getY(), width, padding.getHeight()));
        });

        Optional.ofNullable(map.get("备注")).ifPresent(e -> map.put("备注", padding(e, textHeight.get("备注"))));

        double fixedMid = fphm.getX() - 1;
        Rectangle2D gmfxx = map.get("购买方信息");

        map.put("tl", new Rectangle2D.Double(0, 0, fixedMid, gmfxx.getMinY() - 8.5));
        map.put("tr", new Rectangle2D.Double(fixedMid, 0, width - fixedMid, gmfxx.getMinY() - 8.5));

        final double y = gmfxx.getY();
        this.colorPredicate = (t, c) -> {
            boolean b = t.getY() < y;
            if (!b) {
                return false;
            }
            float[] components = c.getComponents();
            if (components.length == 0) {
                return false;
            }
            return components[0] == 1.0F;
        };

        double minY = map.get("合计").getMinY() + 1;
        double maxY = gmfxx.getMaxY() - textHeight.get("购买方信息") * 0.9 + 1;


        Rectangle2D.Double detailRec = new Rectangle2D.Double(0, maxY, page.getCropBox().getWidth(), minY - maxY);

        List<List<TextPosition>> detailLines = detailLines(page, detailRec);


        map.forEach(this::addRegion);

        this.extractRegions(page);
        Map<String, String> result = map.entrySet().stream()
                .filter(entry -> getRegions().contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> Optional.ofNullable(this.getTextForRegion(e.getKey())).map(t -> REPLACEMENTS.entrySet().stream().reduce(t, (s, en) -> s.replaceAll(en.getKey(), en.getValue()), (a, b) -> a)).orElse("")));
        String reduce = result.entrySet().stream()
                .map(e -> "----------" + e.getKey() + "----------\n" + e.getValue())
                .reduce("", (a, b) -> a + b);
        log.info("解析内容: \n{}", reduce);

        String r = detailLines.stream()
                .map(l -> l.stream().map(TextPosition::getUnicode).collect(Collectors.joining()))
                .collect(Collectors.joining("\n"));
        log.info("解析详情内容: \n{}", r);
        parseInvoice(result, detailLines);

    }

    public String cleanTitle(String str) {
        String trim = Optional.ofNullable(str).map(String::trim).map(e -> e.replaceAll("\\x20", "")).orElse("");
        int tidx = Optional.of(trim.lastIndexOf("）")).filter(e -> e != -1).map(e -> e + 1).orElseGet(() -> trim.lastIndexOf("发票") + 2);
        String pre = trim.substring(0, tidx);
        String next = trim.substring(tidx);
        return pre.replaceAll("\\s", "") + next;
    }

    public List<List<TextPosition>> detailLines(PDPage page, Rectangle2D rectangle2D) throws IOException {
        List<List<TextPosition>> lines = new ArrayList<>();
        PDFTextStripperByArea dStripper = new PDFTextStripperByArea() {
            boolean newLine = true;

            @Override
            protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
                if (newLine) {
                    lines.add(new ArrayList<>());
                    newLine = false;
                }
                lines.get(lines.size() - 1).addAll(textPositions);
            }


            @Override
            protected void writeLineSeparator() throws IOException {
                newLine = true;
            }

        };
        dStripper.setSortByPosition(true);
        dStripper.addRegion("detail", rectangle2D);
        dStripper.extractRegions(page);
        return lines;
    }


    public String replace(String str) {
        return REPLACEMENTS.entrySet().stream().reduce(str, (t, e) -> t.replaceAll(e.getKey(), e.getValue()), (t, e) -> t + e);
    }

    private void parseInvoice(Map<String, String> result, List<List<TextPosition>> detailLines) {
        Invoice invoice = new Invoice();

        Optional.ofNullable(result.get("tl")).map(this::cleanTitle).ifPresent(e -> {
            boolean ptfp = e.contains("普通发票");
            invoice.setType(ptfp ? "普通发票" : "专用发票");
            String title = get(Pattern.compile("[^\n\r]+"), e, 0);
            invoice.setTitle(title);
            String jqbh = get(Pattern.compile("机器编号\\S\\x20?([^\n\r]+)"), e, 1);
            invoice.setMachineNumber(jqbh);

        });
        Optional.ofNullable(result.get("密码区")).map(String::trim).ifPresent(invoice::setPassword);
        Optional.ofNullable(result.get("备注")).map(String::trim).ifPresent(invoice::setRemark);

        Optional.ofNullable(result.get("价税合计")).ifPresent(e -> {
            String jshj = Objects.requireNonNull(get(Pattern.compile("价税合计[^0-9-]+(-?\\d+(\\.\\d+)?)"), e, 1), "总价未知: " + e);
            invoice.setTotalAmountString(jshj);
            invoice.setTotalAmount(new BigDecimal(jshj));
        });
        Optional.ofNullable(result.get("合计")).ifPresent(e -> {
            List<String> hj = findAll(Pattern.compile("-?\\d+(\\.\\d+)?"), e, 0);
            if (hj.size() < 2) hj.add("0");
            invoice.setAmount(new BigDecimal(hj.get(0)));
            invoice.setTaxAmount(new BigDecimal(hj.get(1)));
        });
        Optional.ofNullable(result.get("tr")).ifPresent(e -> {
            List<String> list = Arrays.asList("发\\x20?票\\x20?代\\x20?码", "发\\x20?票\\x20?号\\x20?码", "开\\x20?票\\x20?日\\x20?期", "校\\x20?验\\x20?码");
            Map<Integer, String> collect = IntStream.range(0, list.size())
                    .boxed()
                    .collect(HashMap::new, (m, i) -> m.put(i, get(Pattern.compile(list.get(i) + "\\x20?\\S\\x20?([^\n\r]+)"), e, 1)), HashMap::putAll);
            invoice.setCode(collect.get(0));
            invoice.setNumber(collect.get(1));
            Optional.ofNullable(collect.get(2))
                    .map(t -> t.replaceAll("\\s", ""))
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(invoice::setDate);
            invoice.setChecksum(collect.get(3));

        });
        List<String> subject = Arrays.asList("名.*称", "纳\\x20?税\\x20?人\\x20?识\\x20?别\\x20?号", "地[址、电\\s]+话", "开\\x20?户\\x20?行\\x20?及\\x20?账\\x20?号");

        Optional.ofNullable(result.get("购买方信息")).map(this::replace).ifPresent(e -> {
            Map<Integer, String> collect = IntStream.range(0, subject.size())
                    .boxed()
                    .collect(HashMap::new, (m, i) -> m.put(i, get(Pattern.compile(subject.get(i) + "\\x20*\\S\\x20?([^\n\r]+)"), e, 1)), HashMap::putAll);
            invoice.setBuyerName(collect.get(0));
            Optional.ofNullable(collect.get(1)).map(t -> t.replaceAll("\\x20", "")).ifPresent(invoice::setBuyerCode);
//            invoice.setBuyerCode(collect.get(1));
            invoice.setBuyerAddress(collect.get(2));
            invoice.setBuyerAccount(collect.get(3));
        });

        Optional.ofNullable(result.get("销售方信息")).ifPresent(e -> {
            Map<Integer, String> collect = IntStream.range(0, subject.size())
                    .boxed()
                    .collect(HashMap::new, (m, i) -> m.put(i, get(Pattern.compile(subject.get(i) + "\\x20*\\S\\x20?([^\n\r]+)"), e, 1)), HashMap::putAll);
            invoice.setSellerName(collect.get(0));
            Optional.ofNullable(collect.get(1)).map(t -> t.replaceAll("\\x20", "")).ifPresent(invoice::setSellerCode);
//            invoice.setSellerCode(collect.get(1));
            invoice.setSellerAddress(collect.get(2));
            invoice.setSellerAccount(collect.get(3));
        });

        Optional.ofNullable(result.get("开票人")).map(this::replace).ifPresent(e -> {
            List<String> list = Arrays.asList("收\\s*款\\s*人", "复\\s*核", "开\\s*票\\s*人");
            Map<Integer, String> collect = IntStream.range(0, list.size())
                    .boxed()
                    .collect(HashMap::new, (m, i) -> m.put(i, get(Pattern.compile(list.get(i) + "\\x20?\\S\\x20?(\\S+)"), e, 1)), HashMap::putAll);
            invoice.setPayee(collect.get(0));
            invoice.setReviewer(collect.get(1));
            invoice.setDrawer(collect.get(2));

        });

        List<TextPosition> titleLine = detailLines.isEmpty() ? Collections.emptyList() : detailLines.get(0);

        String titles = titleLine.stream().map(TextPosition::getUnicode).collect(Collectors.joining());


        List<Pair<Integer, List<TextPosition>>> pairs = IntStream.range(0, PATTERNS.size()).boxed().map(i -> Pair.of(i, indexOf(PATTERNS.get(i), titles)))
                .filter(e -> Objects.nonNull(e.getRight()))
                .map(e -> Pair.of(e.getLeft(), titleLine.subList(e.getRight().start(), e.getRight().end())))
                .collect(Collectors.toList());

        List<Pair<Integer, Rectangle2D>> pairRec = pairs.stream().map(e -> Pair.of(e.getLeft(), getRectangle2D(e.getRight()))).sorted(Comparator.comparingDouble(e -> e.getRight().getX())).collect(Collectors.toList());


        List<Pair<Integer, Pair<Double, Double>>> titleArea = IntStream.range(0, pairRec.size()).boxed().reduce(new ArrayList<Pair<Integer, Pair<Double, Double>>>(), (l, i) -> {
            Pair<Integer, Rectangle2D> pair = pairRec.get(i);
            Double left = i == 0 ? 0D : pairRec.get(i - 1).getRight().getMaxX();
            Double right = i == pairRec.size() - 1 ? Double.MAX_VALUE : pairRec.get(i + 1).getRight().getMinX();
            l.add(Pair.of(pair.getLeft(), Pair.of(left, right)));
            return l;
        }, (a, b) -> a);

        List<List<List<TextPosition>>> detailRec = detailLines.stream().skip(1)
                .filter(e -> e.stream().anyMatch(t -> Objects.equals(t.getUnicode(), "*")))
                .map(list -> list.stream().reduce(new ArrayList<List<TextPosition>>(), (l, e) -> {
                    boolean empty = l.isEmpty();
                    List<TextPosition> curList = l.isEmpty() ? new ArrayList<>(Collections.singletonList(e)) : l.get(l.size() - 1);
                    if (empty) {
                        l.add(curList);
                        return l;
                    }
                    TextPosition textPosition = curList.get(curList.size() - 1);
                    boolean siblings = e.getX() - textPosition.getX() <= textPosition.getWidthDirAdj() + 0.01;
                    if (siblings) {
                        curList.add(e);
                    } else {
                        l.add(new ArrayList<>(Collections.singletonList(e)));
                    }
                    return l;
                }, (a, b) -> a)).collect(Collectors.toList());


        Function<List<TextPosition>, Pair<Double, Double>> position = (l) -> Pair.of((double) l.get(0).getX(), (double) l.get(l.size() - 1).getEndX());

        Function<List<TextPosition>, String> text = (ls) -> Optional.ofNullable(ls).map(l -> l.stream().map(TextPosition::getUnicode).collect(Collectors.joining())).orElse(null);
        // 详情第一个名称
        detailRec.stream().findFirst().flatMap(e -> e.stream().findFirst()).map(text).map(e -> e.replaceAll("\\*", " ").trim()).ifPresent(invoice::setFirstRecName);

        List<Map<Integer, String>> collect = detailRec.stream().map(l -> {
            return titleArea.stream().map(e -> {
                Pair<Double, Double> right = e.getRight();
                Double mid = right.getLeft() + (right.getRight() - right.getLeft()) / 2;
                List<TextPosition> list = l.stream().map(t -> {

                            Pair<Double, Double> applied = position.apply(t);
                            boolean overlap = isOverlap(right.getLeft(), right.getRight(), applied.getLeft(), applied.getRight());
                            if (!overlap) return null;
                            Double min = Math.min(Math.abs(applied.getLeft() - mid), Math.abs(applied.getRight() - mid));
                            return Pair.of(min, t);
                        }).filter(Objects::nonNull)
                        .min(Comparator.comparingDouble(Pair::getLeft))
                        .map(Pair::getRight).orElse(null);
                // 消除影响
                l.remove(list);
                return Pair.of(e.getLeft(), list);
            }).<Map<Integer, String>>collect(HashMap::new, (m, e) -> m.put(e.getLeft(), text.apply(e.getRight())), Map::putAll);
        }).collect(Collectors.toList());

        List<Detail> detailList = collect.stream().map(e -> {
            Detail detail = new Detail();
            detail.setName(e.get(0));
            detail.setModel(e.get(1));
            detail.setUnit(e.get(2));
            detail.setCount(e.get(3));
            detail.setPrice(e.get(4));
            detail.setAmount(e.get(5));
            detail.setTaxRate(e.get(6));
            detail.setTaxAmount(e.get(7));
            return detail;
        }).collect(Collectors.toList());

        invoice.setDetailList(detailList);


        this.invoice = invoice;
    }


    /**
     * 计算文字二维空间
     *
     * @param positions
     * @return
     */
    public Rectangle2D getRectangle2D(List<TextPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return null;
        }
        TextPosition first = positions.get(0);
        TextPosition last = positions.get(positions.size() - 1);


        return new Rectangle2D.Double(first.getX(), first.getY(), last.getX() - first.getX() + last.getWidthDirAdj(), last.getY() - first.getY() + last.getYScale());
    }


    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        String collect = textPositions.stream().filter(e -> !this.textPositions.contains(e)).map(TextPosition::getUnicode).collect(Collectors.joining(""));
        if (Objects.equals("", collect)) {
            return;
        }

        super.writeString(collect, textPositions);
    }


    @Override
    protected void endPage(PDPage page) throws IOException {
        // 清理红颜色文字集合
        textPositions.clear();
        super.endPage(page);


    }


    @Override
    protected void processTextPosition(TextPosition text) {

        String unicode = REPLACEMENTS.getOrDefault(text.getUnicode(), text.getUnicode());

//        System.out.printf("%s %s%n", unicode, text.getX() + "," + text.getY());

        if (detachColorText && Objects.nonNull(colorPredicate)) {
            PDColor color = getGraphicsState().getNonStrokingColor();
            if (colorPredicate.test(text, color)) {
                textPositions.add(text);
            }
        }

        super.processTextPosition(text);

        if (parsedPosition) {
            return;
        }

        if (StringUtils.isBlank(unicode)) {
            return;
        }


        orVerticalText.forEach((k, v) -> {
            if (!k.contains(unicode)) {
                return;
            }
            v.add(text);
        });

        List<Map.Entry<String, List<TextPosition>>> list = new ArrayList<>(horizonText.entrySet());
        IntStream.range(0, list.size()).forEach(i -> {
            Map.Entry<String, List<TextPosition>> entry = list.get(i);
            String k = entry.getKey();
            List<TextPosition> v = entry.getValue();
            if (v.size() >= k.length()) return;

            if (!Objects.equals(k.charAt(v.size()) + "", unicode)) {
                if (!Objects.equals(v.size(), k.length())) {
                    v.clear();
                }
                return;
            }
            boolean b = list.subList(0, i).stream().filter(e -> e.getKey().contains(k)).flatMap(e -> e.getValue().stream()).anyMatch(e -> Objects.equals(e, text));
            if (b) {
                return;
            }

            String c = Objects.toString(k.charAt(v.size()));
            if (Objects.equals(c, unicode)) {
                v.add(text);
            }
        });


    }


    // ========================== tools


    public static List<String> findAll(Pattern pattern, CharSequence content, int group) {
        return findAll(pattern, content, group, new ArrayList<>());
    }

    public static String get(Pattern pattern, CharSequence content, int groupIndex) {
        if (null != content && null != pattern) {
            AtomicReference<String> result = new AtomicReference<>();
            get(pattern, content, (matcher) -> {
                result.set(matcher.group(groupIndex));
            });
            return result.get();
        } else {
            return null;
        }
    }

    public static String get(Pattern pattern, CharSequence content, String groupName) {
        if (null != content && null != pattern && null != groupName) {
            AtomicReference<String> result = new AtomicReference<>();
            get(pattern, content, (matcher) -> {
                result.set(matcher.group(groupName));
            });
            return result.get();
        } else {
            return null;
        }
    }

    public static void get(Pattern pattern, CharSequence content, Consumer<Matcher> consumer) {
        if (null != content && null != pattern && null != consumer) {
            Matcher m = pattern.matcher(content);
            if (m.find()) {
                consumer.accept(m);
            }

        }
    }

    public static <T extends Collection<String>> T findAll(Pattern pattern, CharSequence content, int group, T collection) {
        if (null != pattern && null != content) {
            findAll(pattern, content, (matcher) -> {
                collection.add(matcher.group(group));
            });
            return collection;
        } else {
            return null;
        }
    }

    public static void findAll(Pattern pattern, CharSequence content, Consumer<Matcher> consumer) {
        if (null != pattern && null != content) {
            Matcher matcher = pattern.matcher(content);

            while (matcher.find()) {
                consumer.accept(matcher);
            }

        }
    }

    public static MatchResult indexOf(Pattern pattern, CharSequence content) {
        if (null != pattern && null != content) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.toMatchResult();
            }
        }

        return null;
    }

    public static boolean isOverlap(Double realStartTime, Double realEndTime, Double startTime, Double endTime) {
        return realStartTime.compareTo(endTime) <= 0 && startTime.compareTo(realEndTime) <= 0;
    }

}
