package com.github.brokengeki.util


@Suppress("unused")
class MultiMap<K, out V> {
    private var mData : ArrayList<Pair<K, V>>

    constructor() { mData = ArrayList() }
    constructor(collections: Collection<Pair<K, V>>) { mData = ArrayList(collections) }
    constructor(vararg elements: Pair<K, V>) { mData = arrayListOf(*elements) }
    constructor(initialCapacity: Int) { mData = ArrayList(initialCapacity) }

    fun isEmpty(): Boolean = mData.isEmpty()

    fun containsKey(key: K): Boolean {
        mData.forEach {
            if (it.first == key)
                return true
        }
        return false
    }

    fun containsValue(value: @UnsafeVariance V): Boolean {
        mData.forEach {
            if (it.second == value)
                return true
        }
        return false
    }

    operator fun get(key: K): Collection<V> {
        val result = ArrayList<V>()
        mData.forEach {
            if (it.first == key)
                result.add(it.second)
        }
        return result
    }

    fun put(element: Pair<K, @UnsafeVariance V>) { mData.add(element) }

    fun putAll(from: Collection<Pair<K, @UnsafeVariance V>>) { mData.addAll(from) }

    fun removeFirst(key: K): Boolean {
        mData.forEach {
            if (it.first == key) {
                mData.remove(it)
                return true
            }
        }
        return false
    }

    fun removeAll(key: K): Boolean {
        var result = false
        mData.forEach {
            if (it.first == key) {
                mData.remove(it)
                result = true
            }
        }
        return result
    }

    fun clear() { mData.clear() }

    val size: Int
        get() = mData.size

    val values: List<V>
        get() {
            val result = mutableSetOf<V>()
            mData.forEach { result.add(it.second) }
            return result.toList()
        }
}
fun <K, V> multiMapOf(vararg elements: Pair<K, V>) = if (elements.isEmpty()) MultiMap() else MultiMap(*elements)