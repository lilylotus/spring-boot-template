package org.example.simple.algorithm;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;

/**
 * 有序键值结构：按键的自然顺序维护键值对，支持增删查、最值、前驱后继导航与闭区间范围查询。
 * <p>
 * 实现类：{@link BinarySearchTree}、{@link RedBlackTree}、{@link AvlTree}、{@link BTree}、{@link BPlusTree}，
 * 它们对外行为一致，可相互替换以对比性能与结构特征。
 * <p>
 * 通用约定：
 * <ul>
 *   <li>键不允许为 {@code null}，任一接受键的方法收到 {@code null} 时抛出 {@link NullPointerException}；</li>
 *   <li>值允许为 {@code null}，因此 {@link #get} 返回 {@code null} 时需用 {@link #containsKey} 区分"不存在"
 *   与"值为 null"；</li>
 *   <li>键相等仅依据 {@code compareTo} 判定（与 {@link java.util.TreeMap} 一致）；</li>
 *   <li>迭代器按键升序返回不可变的 {@link Map.Entry} 快照，fail-fast：迭代期间插入新键或删除键后继续迭代
 *   抛出 {@link ConcurrentModificationException}，覆盖已有键的值不算结构修改；迭代器不支持 {@code remove}；</li>
 *   <li>非线程安全。</li>
 * </ul>
 *
 * @param <K> 键类型，按自然顺序比较
 * @param <V> 值类型
 */
public interface OrderedMap<K extends Comparable<? super K>, V> extends Iterable<Map.Entry<K, V>> {

    /**
     * 写入键值对。
     *
     * @param key 键，不允许为 {@code null}
     * @param value 值，允许为 {@code null}
     * @return 键已存在时返回旧值（并覆盖为新值），否则返回 {@code null}
     */
    V put(K key, V value);

    /**
     * 返回键对应的值。
     *
     * @param key 键，不允许为 {@code null}
     * @return 键对应的值，键不存在时返回 {@code null}
     */
    V get(K key);

    /**
     * 判断键是否存在。
     *
     * @param key 键，不允许为 {@code null}
     * @return 键存在时返回 {@code true}
     */
    boolean containsKey(K key);

    /**
     * 删除键。
     *
     * @param key 键，不允许为 {@code null}
     * @return 键存在时返回被删除的值，否则返回 {@code null}
     */
    V remove(K key);

    /**
     * 返回键值对个数。
     *
     * @return 键值对个数
     */
    int size();

    /**
     * 判断是否为空。
     *
     * @return 没有任何键值对时返回 {@code true}
     */
    default boolean isEmpty() {
        return size() == 0;
    }

    /** 删除全部键值对。 */
    void clear();

    /**
     * 返回最小键。
     *
     * @return 最小键
     * @throws NoSuchElementException 结构为空时抛出
     */
    K minKey();

    /**
     * 返回最大键。
     *
     * @return 最大键
     * @throws NoSuchElementException 结构为空时抛出
     */
    K maxKey();

    /**
     * 返回小于等于给定键的最大键。
     *
     * @param key 参照键，不允许为 {@code null}
     * @return 小于等于参照键的最大键，不存在时返回 {@code null}
     */
    K floorKey(K key);

    /**
     * 返回大于等于给定键的最小键。
     *
     * @param key 参照键，不允许为 {@code null}
     * @return 大于等于参照键的最小键，不存在时返回 {@code null}
     */
    K ceilingKey(K key);

    /**
     * 按升序返回全部键的快照列表。
     *
     * @return 升序键列表，修改它不会影响本结构
     */
    default List<K> keys() {
        List<K> result = new ArrayList<>(size());
        for (Map.Entry<K, V> entry : this) {
            result.add(entry.getKey());
        }
        return result;
    }

    /**
     * 按升序返回键位于闭区间 {@code [fromInclusive, toInclusive]} 内的键值对快照列表。
     *
     * @param fromInclusive 区间下界（包含），不允许为 {@code null}
     * @param toInclusive 区间上界（包含），不允许为 {@code null}
     * @return 区间内的键值对；{@code fromInclusive > toInclusive} 时返回空列表
     */
    List<Map.Entry<K, V>> range(K fromInclusive, K toInclusive);

    /**
     * 按键升序对每个键值对执行操作。
     *
     * @param action 接收键与值的操作
     */
    default void forEach(BiConsumer<? super K, ? super V> action) {
        for (Map.Entry<K, V> entry : this) {
            action.accept(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 返回树高。
     *
     * @return 二叉树为根到最深节点路径上的节点数，多路树为层数；空结构返回 0
     */
    int height();
}
