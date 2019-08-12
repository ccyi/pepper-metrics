package com.pepper.metrics.extension.scheduled;

import com.pepper.metrics.core.Stats;
import com.pepper.metrics.extension.scheduled.domain.PrinterDomain;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Description:
 *
 * @author zhiminxu
 * @package com.pepper.metrics.extension.scheduled
 * @create_time 2019-08-07
 */
public abstract class AbstractPerfPrinter implements PerfPrinter {

    private static final String SPLIT = "| ";
    private static final int LABEL_SIZE_METRICS = 75;
    private static final int LABEL_SIZE_MAX = 10;
    private static final int LABEL_SIZE_CONCURRENT = 11;
    private static final int LABEL_SIZE_ERR = 10;
    private static final int LABEL_SIZE_SUM = 10;
    private static final int LABEL_SIZE_P90 = 10;
    private static final int LABEL_SIZE_P99 = 10;
    private static final int LABEL_SIZE_P999 = 10;
    private static final int LABEL_SIZE_QPS = 8;
    private static final int LABEL_SIZE = LABEL_SIZE_METRICS +
            LABEL_SIZE_MAX +
            LABEL_SIZE_CONCURRENT +
            LABEL_SIZE_ERR +
            LABEL_SIZE_SUM +
            LABEL_SIZE_P90 +
            LABEL_SIZE_P99 +
            LABEL_SIZE_P999 +
            LABEL_SIZE_QPS +
            SPLIT.length() * 2;

    private static final Logger pLogger = LoggerFactory.getLogger("performance");

    protected static String PREFIX = "";

    @Override
    public void print(Set<Stats> statsSet) {
        List<Stats> stats = chooseStats(statsSet);
        // 记录当前时间窗口的error数
        ConcurrentMap<String, ConcurrentMap<List<String>, Double>> currentErrCollector = new ConcurrentHashMap<>();
        ConcurrentMap<String, ConcurrentMap<List<String>, Long>> currentSummaryCollector = new ConcurrentHashMap<>();

        for (Stats stat : stats) {
            setPre(stat);
            List<PrinterDomain> printerDomains = collector(stat, currentErrCollector, currentSummaryCollector);

            String prefixStr = "[" + PREFIX + "] - ";
            String line = StringUtils.repeat("-", LABEL_SIZE);

            pLogger.info(prefixStr + line);

            String header = prefixStr + SPLIT +
                    StringUtils.rightPad("Metrics", LABEL_SIZE_METRICS) +
                    StringUtils.leftPad("Max(ms)", LABEL_SIZE_MAX) +
                    StringUtils.leftPad("Concurrent", LABEL_SIZE_CONCURRENT) +
                    StringUtils.leftPad("Error", LABEL_SIZE_ERR) +
                    StringUtils.leftPad("Count", LABEL_SIZE_SUM) +
                    StringUtils.leftPad("P90(ms)", LABEL_SIZE_P90) +
                    StringUtils.leftPad("P99(ms)", LABEL_SIZE_P99) +
                    StringUtils.leftPad("P999(ms)", LABEL_SIZE_P999) +
                    StringUtils.leftPad("Qps", LABEL_SIZE_QPS)+
                    " " + SPLIT;
            pLogger.info(header);

            for (PrinterDomain domain : printerDomains) {
                String content = prefixStr + SPLIT +
                        StringUtils.rightPad(domain.getTag(), LABEL_SIZE_METRICS) +
                        StringUtils.leftPad(String.format("%.1f", Float.parseFloat(domain.getMax())), LABEL_SIZE_MAX) +
                        StringUtils.leftPad(String.format("%.0f", Float.parseFloat(domain.getConcurrent())), LABEL_SIZE_CONCURRENT) +
                        StringUtils.leftPad(String.format("%.0f", Float.parseFloat(domain.getErr())), LABEL_SIZE_ERR) +
                        StringUtils.leftPad(String.format("%.0f", Float.parseFloat(domain.getSum())), LABEL_SIZE_SUM) +
                        StringUtils.leftPad(String.format("%.1f", Float.parseFloat(domain.getP90())), LABEL_SIZE_P90) +
                        StringUtils.leftPad(String.format("%.1f", Float.parseFloat(domain.getP99())), LABEL_SIZE_P99) +
                        StringUtils.leftPad(String.format("%.1f", Float.parseFloat(domain.getP999())), LABEL_SIZE_P999) +
                        StringUtils.leftPad(String.format("%.1f", Float.parseFloat(domain.getQps())), LABEL_SIZE_QPS) +
                        " " + SPLIT ;
                pLogger.info(content);
            }
            pLogger.info(prefixStr + line);
        }

        LastTimeStatsHolder.lastTimeErrCollector = currentErrCollector;
        LastTimeStatsHolder.lastTimeSummaryCollector = currentSummaryCollector;
    }

    private void setPre(Stats stats) {
        PREFIX = setPrefix(stats);
    }

    /**
     * 日志前缀的默认实现
     * @param stats
     * @return
     */
    @Override
    public String setPrefix(Stats stats) {
        return "pref-" + stats.getName() + "-" + stats.getNamespace();
    }

    private List<PrinterDomain> collector(Stats stats, ConcurrentMap<String, ConcurrentMap<List<String>, Double>> currentErrCollector,
                                          ConcurrentMap<String, ConcurrentMap<List<String>, Long>> currentSummaryCollector) {
        ConcurrentMap<List<String>, Counter> errCollector = stats.getErrCollector();
        ConcurrentMap<List<String>, AtomicLong> gaugeCollector = stats.getGaugeCollector();
        ConcurrentMap<List<String>, Timer> summaryCollector = stats.getSummaryCollector();

        // 记录上一次的error数
        currentErrCollector.put(buildCollectorKey(stats), parseErrCollector(errCollector));
        currentSummaryCollector.put(buildCollectorKey(stats), parseSummaryCollector(summaryCollector));

        List<PrinterDomain> retList = new ArrayList<>();

        for (Map.Entry<List<String>, Timer> entry : summaryCollector.entrySet()) {
            List<String> tag = entry.getKey();
            Timer summary= entry.getValue();

            Counter counter = errCollector.get(tag);
            AtomicLong concurrent = gaugeCollector.get(tag);

            PrinterDomain domain = new PrinterDomain();
            String name = "unknown";
            if (tag.size() > 1) {
                name = tag.get(1);
            }
            HistogramSnapshot snapshot = summary.takeSnapshot();

            domain.setTag(name);

            domain.setConcurrent(concurrent == null ? "0" : concurrent.toString());
            domain.setErr(counter == null ? "0" : String.valueOf(counter.count() - getLastTimeErrCount(stats, entry.getKey())));
            domain.setSum(String.valueOf(snapshot.count() - getLastTimeSummaryCount(stats, entry.getKey())));
            ValueAtPercentile[] vps = snapshot.percentileValues();
            for (ValueAtPercentile vp : vps) {
                if (vp.percentile() == 0.9D) {
                    domain.setP90(String.valueOf(vp.value(TimeUnit.MILLISECONDS)));
                } else if (vp.percentile() == 0.99D) {
                    domain.setP99(String.valueOf(vp.value(TimeUnit.MILLISECONDS)));
                } else if (vp.percentile() == 0.999D) {
                    domain.setP999(String.valueOf(vp.value(TimeUnit.MILLISECONDS)));
                } else if (vp.percentile() == 0.99999D) {
                    domain.setMax(String.valueOf(vp.value(TimeUnit.MILLISECONDS)));
                }
            }

            // 计算qps
            domain.setQps(String.format("%.1f", Float.parseFloat(domain.getSum()) / 60));

            retList.add(domain);
        }

        return retList;
    }

    private ConcurrentMap<List<String>, Long> parseSummaryCollector(ConcurrentMap<List<String>, Timer> summaryCollector) {
        ConcurrentMap<List<String>, Long> map = new ConcurrentHashMap<>();

        for (Map.Entry<List<String>, Timer> entry : summaryCollector.entrySet()) {
            map.put(entry.getKey(), entry.getValue().count());
        }

        return map;
    }

    private ConcurrentMap<List<String>, Double> parseErrCollector(ConcurrentMap<List<String>, Counter> errCollector) {
        ConcurrentMap<List<String>, Double> map = new ConcurrentHashMap<>();
        for (Map.Entry<List<String>, Counter> entry : errCollector.entrySet()) {
            map.put(entry.getKey(), entry.getValue().count());
        }

        return map;
    }

    private long getLastTimeSummaryCount(Stats stats, List<String> key) {
        ConcurrentMap<List<String>, Long> map = LastTimeStatsHolder.lastTimeSummaryCollector.get(buildCollectorKey(stats));
        if (map == null) {
            return 0L;
        }

        Long summary = map.get(key);
        return summary == null ? 0L : summary;
    }

    private double getLastTimeErrCount(Stats stats, List<String> key) {
        ConcurrentMap<List<String>, Double> map = LastTimeStatsHolder.lastTimeErrCollector.get(buildCollectorKey(stats));
        if (map == null) {
            return 0.0D;
        }

        Double counter = map.get(key);
        return counter == null ? 0.0D : counter;
    }

    private String buildCollectorKey(Stats stats) {
        return stats.getName() + "-" + stats.getNamespace();
    }

}