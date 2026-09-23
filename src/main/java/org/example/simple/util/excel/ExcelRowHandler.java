package org.example.simple.util.excel;

/**
 * 同步处理一行 XLSX 数据的函数式接口。
 * <p>
 * 回调在解析线程中串行执行，不应长期阻塞。大数据场景可在回调中分批持久化，避免把全部行累计到内存。
 *
 * @param <T> 行数据类型
 */
@FunctionalInterface
public interface ExcelRowHandler<T> {

    /**
     * 处理一行数据。
     *
     * @param value 当前行数据
     * @param context 当前行位置
     * @return {@code true} 继续读取，{@code false} 正常提前停止
     * @throws Exception 调用方处理失败时抛出
     */
    boolean handle(T value, ExcelRowContext context) throws Exception;
}
