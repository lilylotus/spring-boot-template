package org.example.simple.algorithm;

import java.util.AbstractMap;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * {@link OrderedMap} 各实现共用的包级工具方法：键校验、不可变键值对创建与 fail-fast 迭代器包装。
 */
final class OrderedMaps {

    /** 工具类不允许实例化。 */
    private OrderedMaps() {
    }

    /**
     * 校验键非 {@code null} 并原样返回。
     *
     * @throws NullPointerException 键为 {@code null} 时抛出
     */
    static <K> K requireKey(K key) {
        return Objects.requireNonNull(key, "键不允许为 null");
    }

    /** 创建不可变键值对快照；值允许为 {@code null}，因此不使用 {@link Map#entry}。 */
    static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    /**
     * 将迭代器包装为 fail-fast 迭代器：每次 {@code next} 前比较结构修改次数，与创建时不同则抛出
     * {@link ConcurrentModificationException}。
     *
     * @param delegate 实际执行遍历的迭代器
     * @param modCount 读取当前结构修改次数的函数
     */
    static <T> Iterator<T> failFast(Iterator<T> delegate, IntSupplier modCount) {
        int expectedModCount = modCount.getAsInt();
        return new Iterator<>() {

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public T next() {
                if (modCount.getAsInt() != expectedModCount) {
                    throw new ConcurrentModificationException("迭代期间结构被修改");
                }
                return delegate.next();
            }
        };
    }
}
