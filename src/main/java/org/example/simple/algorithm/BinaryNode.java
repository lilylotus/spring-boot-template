package org.example.simple.algorithm;

/**
 * 二叉树节点基类，仅包含键、值与左右子节点；具体树的节点类继承它并补充平衡所需的字段。
 * <p>
 * 采用自限定泛型 {@code N}，使 {@link BinaryTrees} 中的通用算法返回的仍是具体节点类型。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @param <N> 具体节点类型
 */
abstract class BinaryNode<K, V, N extends BinaryNode<K, V, N>> {

    /** 节点键。 */
    K key;

    /** 节点值。 */
    V value;

    /** 左子节点，其子树中所有键均小于本节点键。 */
    N left;

    /** 右子节点，其子树中所有键均大于本节点键。 */
    N right;

    /** 使用键值创建节点。 */
    BinaryNode(K key, V value) {
        this.key = key;
        this.value = value;
    }
}
