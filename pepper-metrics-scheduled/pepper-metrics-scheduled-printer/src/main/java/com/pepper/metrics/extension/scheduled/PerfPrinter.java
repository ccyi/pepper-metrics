package com.pepper.metrics.extension.scheduled;

import com.pepper.metrics.core.Stats;
import com.pepper.metrics.core.extension.Scope;
import com.pepper.metrics.core.extension.Spi;

import java.util.List;
import java.util.Set;

/**
 * Description:
 *  Performance信息日志打印，可通过SPI方式进行扩展。
 *
 * @author zhiminxu
 * @package com.pepper.metrics.extension.scheduled
 * @create_time 2019-08-07
 */
@Spi(scope = Scope.SINGLETON)
public interface PerfPrinter {

    /**
     * 选取需要打印的Stats，通常根据name选取，由于同一个name下可能有多个namespace，所以这里会返回一个数组。
     * 数组中的元素通常具有相同的name属性，但具备不同的namespace属性。
     *
     * @param statsSet 统计信息
     * @return 筛选后的集合
     */
    List<Stats> chooseStats(Set<Stats> statsSet);

    /**
     * 打印日志
     * @param statsSet 统计信息
     */
    void print(Set<Stats> statsSet);

    /**
     * <pre>
     * 定义日志前缀，继承AbstractPerfPrinter后，具备默认实现
     *
     * 默认实现前缀格式：
     *      perf-[name]-[namespace]
     * </pre>
     */
    String setPrefix(Stats stats);

}