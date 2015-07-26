/*******************************************************************************
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (c) 2014-2015. The NFSdb project and its contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.nfsdb.collections;

import com.nfsdb.utils.Unsafe;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Arrays;

public class IntList implements Mutable {
    /**
     * The maximum number of runs in merge sort.
     */
    private static final int MAX_RUN_COUNT = 67;
    /**
     * The maximum length of run in merge sort.
     */
    private static final int MAX_RUN_LENGTH = 33;
    /**
     * If the length of an array to be sorted is less than this
     * constant, Quicksort is used in preference to merge sort.
     */
    private static final int QUICKSORT_THRESHOLD = 286;
    /**
     * If the length of an array to be sorted is less than this
     * constant, insertion sort is used in preference to Quicksort.
     */
    private static final int INSERTION_SORT_THRESHOLD = 47;

    private static final int DEFAULT_ARRAY_SIZE = 16;
    private static final int noEntryValue = -1;
    private int[] buffer;
    private int pos = 0;
    private StringBuilder toStringBuilder;

    @SuppressWarnings("unchecked")
    public IntList() {
        this(DEFAULT_ARRAY_SIZE);
    }

    @SuppressWarnings("unchecked")
    public IntList(int capacity) {
        this.buffer = new int[capacity < DEFAULT_ARRAY_SIZE ? DEFAULT_ARRAY_SIZE : capacity];
    }

    public void add(int value) {
        ensureCapacity0(pos + 1);
        Unsafe.arrayPut(buffer, pos++, value);
    }

    public void add(IntList that) {
        int p = pos;
        int s = that.size();
        ensureCapacity(p + s);
        System.arraycopy(that.buffer, 0, this.buffer, p, s);
    }

    public void clear() {
        pos = 0;
        Arrays.fill(buffer, noEntryValue);
    }

    public void clear(int capacity) {
        ensureCapacity0(capacity);
        pos = 0;
        Arrays.fill(buffer, noEntryValue);
    }

    public void ensureCapacity(int capacity) {
        ensureCapacity0(capacity);
        pos = capacity;
    }

    public void extendAndSet(int index, int value) {
        ensureCapacity0(index + 1);
        if (index >= pos) {
            pos = index + 1;
        }
        Unsafe.arrayPut(buffer, index, value);
    }

    public int get(int index) {
        if (index < pos) {
            return Unsafe.arrayGet(buffer, index);
        }
        throw new ArrayIndexOutOfBoundsException(index);
    }

    /**
     * Returns element at the specified position. This method does not do
     * bounds check and may cause memory corruption if index is out of bounds.
     * Instead the responsibility to check bounds is placed on application code,
     * which is often the case anyway, for example in indexed for() loop.
     *
     * @param index of the element
     * @return element at the specified position.
     */
    public int getQuick(int index) {
        return Unsafe.arrayGet(buffer, index);
    }

    /**
     * Returns element at the specified position or null, if element index is
     * out of bounds. This is an alternative to throwing runtime exception or
     * doing preemptive check.
     *
     * @param index position of element
     * @return element at the specified position.
     */
    public int getQuiet(int index) {
        if (index < pos) {
            return Unsafe.arrayGet(buffer, index);
        }
        return noEntryValue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int hashCode = 1;
        for (int i = 0, n = pos; i < n; i++) {
            int v = getQuick(i);
            hashCode = 31 * hashCode + (v == noEntryValue ? 0 : v);
        }
        return hashCode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object that) {
        return this == that || that instanceof IntList && equals((IntList) that);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        if (toStringBuilder == null) {
            toStringBuilder = new StringBuilder();
        }

        toStringBuilder.setLength(0);
        toStringBuilder.append('[');
        for (int i = 0, k = size(); i < k; i++) {
            if (i > 0) {
                toStringBuilder.append(',');
            }
            toStringBuilder.append(get(i));
        }
        toStringBuilder.append(']');
        return toStringBuilder.toString();
    }

    public void insertionSortL(int left, int right) {
    /*
     * Traditional (without sentinel) insertion sort,
     * optimized for server VM, is used in case of
     * the leftmost part.
     */
        for (int i = left, j = i; i < right; j = ++i) {
            int ai = getQuick(i + 1);
            while (ai < getQuick(j)) {
                let(j + 1, j);
                if (j-- == left) {
                    break;
                }
            }
            setQuick(j + 1, ai);
        }
    }

    public void set(int index, int element) {
        if (index < pos) {
            Unsafe.arrayPut(buffer, index, element);
            return;
        }
        throw new ArrayIndexOutOfBoundsException(index);
    }

    public void setQuick(int index, int value) {
        Unsafe.arrayPut(buffer, index, value);
    }

    public int size() {
        return pos;
    }

    /**
     * Sorts the specified array.
     */
    public void sort() {
        sort(0, size() - 1);
    }

    /**
     * Sorts the specified range of the array.
     *
     * @param left  the index of the first element, inclusive, to be sorted
     * @param right the index of the last element, inclusive, to be sorted
     */
    public void sort(int left, int right) {
        // Use Quicksort on small arrays
        if (right - left < QUICKSORT_THRESHOLD) {
            sort(left, right, true);
            return;
        }

        /*
         * Index run[i] is the start of i-th run
         * (ascending or descending sequence).
         */
        int[] run = new int[MAX_RUN_COUNT + 1];
        int count = 0;
        run[0] = left;

        // Check if the array is nearly sorted
        for (int k = left; k < right; run[count] = k) {
            if (getQuick(k) < getQuick(k + 1)) { // ascending
                //noinspection StatementWithEmptyBody
                while (++k <= right && getQuick(k - 1) <= getQuick(k)) ;
            } else if (getQuick(k) > getQuick(k + 1)) { // descending
                //noinspection StatementWithEmptyBody
                while (++k <= right && getQuick(k - 1) >= getQuick(k)) ;
                for (int lo = run[count] - 1, hi = k; ++lo < --hi; ) {
                    swap(lo, hi);
                }
            } else { // equal
                for (int m = MAX_RUN_LENGTH; ++k <= right && getQuick(k - 1) == getQuick(k); ) {
                    if (--m == 0) {
                        sort(left, right, true);
                        return;
                    }
                }
            }

            /*
             * The array is not highly structured,
             * use Quicksort instead of merge sort.
             */
            if (++count == MAX_RUN_COUNT) {
                sort(left, right, true);
                return;
            }
        }

        // Check special cases
        if (run[count] == right++) { // The last run contains one element
            run[++count] = right;
        } else if (count == 1) { // The array is already sorted
            return;
        }

        /*
         * Create temporary array, which is used for merging.
         * Implementation note: variable "right" is increased by 1.
         */

        IntList a;
        IntList b;
        byte odd = 0;
        //noinspection StatementWithEmptyBody
        for (int n = 1; (n <<= 1) < count; odd ^= 1) ;

        if (odd == 0) {
            b = this;
            a = new IntList(this.size());

            //noinspection StatementWithEmptyBody
            for (int i = left - 1; ++i < right; a.setQuick(i, b.getQuick(i))) ;
        } else {
            a = this;
            b = new IntList(this.size());
        }

        // Merging
        for (int last; count > 1; count = last) {
            for (int k = (last = 0) + 2; k <= count; k += 2) {
                int hi = run[k], mi = run[k - 1];
                for (int i = run[k - 2], p = i, q = mi; i < hi; ++i) {
                    if (q >= hi || p < mi && getQuick(p) <= getQuick(q)) {
                        b.setQuick(i, a.getQuick(p++));
                    } else {
                        b.setQuick(i, a.getQuick(q++));
                    }
                }
                run[++last] = hi;
            }
            if ((count & 1) != 0) {
                //noinspection StatementWithEmptyBody
                for (int i = right, lo = run[count - 1]; --i >= lo; b.setQuick(i, a.getQuick(i))) ;
                run[++last] = right;
            }
            IntList t = a;
            a = b;
            b = t;
        }
    }

    public void zero(int value) {
        Arrays.fill(buffer, 0, pos, value);
    }

    @SuppressWarnings("unchecked")
    private void ensureCapacity0(int capacity) {
        int l = buffer.length;
        if (capacity > l) {
            int newCap = Math.max(l << 1, capacity);
            int[] buf = new int[newCap];
            System.arraycopy(buffer, 0, buf, 0, l);
            this.buffer = buf;
        }
    }

    private boolean equals(IntList that) {
        if (this.pos == that.pos) {
            for (int i = 0, n = pos; i < n; i++) {
                int lhs = this.getQuick(i);
                if (lhs == noEntryValue) {
                    return that.getQuick(i) == noEntryValue;
                } else if (lhs == that.getQuick(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void insertionSortR(int left, int right) {
        // Skip the longest ascending sequence.
        do {
            if (left >= right) {
                return;
            }
        } while (getQuick(++left) >= getQuick(left - 1));

                /*
                 * Every element from adjoining part plays the role
                 * of sentinel, therefore this allows us to avoid the
                 * left range check on each iteration. Moreover, we use
                 * the more optimized algorithm, so called pair insertion
                 * sort, which is faster (in the context of Quicksort)
                 * than traditional implementation of insertion sort.
                 */
        for (int k = left; ++left <= right; k = ++left) {
            int a1 = getQuick(k), a2 = getQuick(left);

            if (a1 < a2) {
                a2 = a1;
                a1 = getQuick(left);
            }
            while (a1 < getQuick(--k)) {
                let(k + 2, k);
            }
            setQuick(++k + 1, a1);

            while (a2 < getQuick(--k)) {
                let(k + 1, k);
            }
            setQuick(k + 1, a2);
        }
        int last = getQuick(right);

        while (last < getQuick(--right)) {
            let(right + 1, right);
        }
        setQuick(right + 1, last);
    }

    private void let(int a, int b) {
        Unsafe.arrayPut(buffer, a, Unsafe.arrayGet(buffer, b));
    }

    /**
     * Sorts the specified range of the array by Dual-Pivot Quicksort.
     *
     * @param left     the index of the first element, inclusive, to be sorted
     * @param right    the index of the last element, inclusive, to be sorted
     * @param leftmost indicates if this part is the leftmost in the range
     */
    @SuppressFBWarnings({"CC_CYCLOMATIC_COMPLEXITY"})
    private void sort(int left, int right, boolean leftmost) {
        int length = right - left + 1;

        // Use insertion sort on tiny arrays
        if (length < INSERTION_SORT_THRESHOLD) {
            if (leftmost) {
                insertionSortL(left, right);
            } else {
                insertionSortR(left, right);
            }
            return;
        }

        // Inexpensive approximation of length / 7
        int seventh = (length >> 3) + (length >> 6) + 1;

        /*
         * Sort five evenly spaced elements around (and including) the
         * center element in the range. These elements will be used for
         * pivot selection as described below. The choice for spacing
         * these elements was empirically determined to work well on
         * a wide variety of inputs.
         */
        int e3 = (left + right) >>> 1; // The midpoint
        int e2 = e3 - seventh;
        int e1 = e2 - seventh;
        int e4 = e3 + seventh;
        int e5 = e4 + seventh;

        // Sort these elements using insertion sort
        if (getQuick(e2) < getQuick(e1)) {
            swap(e2, e1);
        }

        if (getQuick(e3) < getQuick(e2)) {
            int t = getQuick(e3);
            let(e3, e2);
            setQuick(e2, t);
            if (t < getQuick(e1)) {
                let(e2, e1);
                setQuick(e1, t);
            }
        }
        if (getQuick(e4) < getQuick(e3)) {
            int t = getQuick(e4);
            let(e4, e3);
            setQuick(e3, t);
            if (t < getQuick(e2)) {
                let(e3, e2);
                setQuick(e2, t);
                if (t < getQuick(e1)) {
                    let(e2, e1);
                    setQuick(e1, t);
                }
            }
        }
        if (getQuick(e5) < getQuick(e4)) {
            int t = getQuick(e5);
            let(e5, e4);
            setQuick(e4, t);
            if (t < getQuick(e3)) {
                let(e4, e3);
                setQuick(e3, t);
                if (t < getQuick(e2)) {
                    let(e3, e2);
                    setQuick(e2, t);
                    if (t < getQuick(e1)) {
                        let(e2, e1);
                        setQuick(e1, t);
                    }
                }
            }
        }

        // Pointers
        int less = left;  // The index of the first element of center part
        int great = right; // The index before the first element of right part

        if (getQuick(e1) != getQuick(e2) && getQuick(e2) != getQuick(e3) && getQuick(e3) != getQuick(e4) && getQuick(e4) != getQuick(e5)) {
            /*
             * Use the second and fourth of the five sorted elements as pivots.
             * These values are inexpensive approximations of the first and
             * second terciles of the array. Note that pivot1 <= pivot2.
             */
            int pivot1 = getQuick(e2);
            int pivot2 = getQuick(e4);

            /*
             * The first and the last elements to be sorted are moved to the
             * locations formerly occupied by the pivots. When partitioning
             * is complete, the pivots are swapped back into their final
             * positions, and excluded from subsequent sorting.
             */
            let(e2, left);
            let(e4, right);

            /*
             * Skip elements, which are less or greater than pivot values.
             */
            //noinspection StatementWithEmptyBody
            while (getQuick(++less) < pivot1) ;
            //noinspection StatementWithEmptyBody
            while (getQuick(--great) > pivot2) ;

            /*
             * Partitioning:
             *
             *   left part           center part                   right part
             * +--------------------------------------------------------------+
             * |  < pivot1  |  pivot1 <= && <= pivot2  |    ?    |  > pivot2  |
             * +--------------------------------------------------------------+
             *               ^                          ^       ^
             *               |                          |       |
             *              less                        k     great
             *
             * Invariants:
             *
             *              all in (left, less)   < pivot1
             *    pivot1 <= all in [less, k)     <= pivot2
             *              all in (great, right) > pivot2
             *
             * Pointer k is the first index of ?-part.
             */
            outer:
            for (int k = less - 1; ++k <= great; ) {
                int ak = getQuick(k);
                if (ak < pivot1) { // Move a[k] to left part
                    let(k, less);
                    /*
                     * Here and below we use "a[i] = b; i++;" instead
                     * of "a[i++] = b;" due to performance issue.
                     */
                    setQuick(less, ak);
                    ++less;
                } else if (ak > pivot2) { // Move a[k] to right part
                    while (getQuick(great) > pivot2) {
                        if (great-- == k) {
                            break outer;
                        }
                    }
                    if (getQuick(great) < pivot1) { // a[great] <= pivot2
                        let(k, less);
                        let(less, great);
                        ++less;
                    } else { // pivot1 <= a[great] <= pivot2
                        let(k, great);
                    }
                    /*
                     * Here and below we use "a[i] = b; i--;" instead
                     * of "a[i--] = b;" due to performance issue.
                     */
                    setQuick(great, ak);
                    --great;
                }
            }

            // Swap pivots into their final positions
            let(left, less - 1);
            setQuick(less - 1, pivot1);
            let(right, great + 1);
            setQuick(great + 1, pivot2);

            // Sort left and right parts recursively, excluding known pivots
            sort(left, less - 2, leftmost);
            sort(great + 2, right, false);

            /*
             * If center part is too large (comprises > 4/7 of the array),
             * swap internal pivot values to ends.
             */
            if (less < e1 && e5 < great) {
                /*
                 * Skip elements, which are equal to pivot values.
                 */
                while (getQuick(less) == pivot1) {
                    ++less;
                }

                while (getQuick(great) == pivot2) {
                    --great;
                }

                /*
                 * Partitioning:
                 *
                 *   left part         center part                  right part
                 * +----------------------------------------------------------+
                 * | == pivot1 |  pivot1 < && < pivot2  |    ?    | == pivot2 |
                 * +----------------------------------------------------------+
                 *              ^                        ^       ^
                 *              |                        |       |
                 *             less                      k     great
                 *
                 * Invariants:
                 *
                 *              all in (*,  less) == pivot1
                 *     pivot1 < all in [less,  k)  < pivot2
                 *              all in (great, *) == pivot2
                 *
                 * Pointer k is the first index of ?-part.
                 */
                outer:
                for (int k = less - 1; ++k <= great; ) {
                    int ak = getQuick(k);
                    if (ak == pivot1) { // Move a[k] to left part
                        let(k, less);
                        setQuick(less, ak);
                        ++less;
                    } else if (ak == pivot2) { // Move a[k] to right part
                        while (getQuick(great) == pivot2) {
                            if (great-- == k) {
                                break outer;
                            }
                        }
                        if (getQuick(great) == pivot1) { // a[great] < pivot2
                            let(k, less);
                            /*
                             * Even though a[great] equals to pivot1, the
                             * assignment a[less] = pivot1 may be incorrect,
                             * if a[great] and pivot1 are floating-point zeros
                             * of different signs. Therefore in float and
                             * double sorting methods we have to use more
                             * accurate assignment a[less] = a[great].
                             */
                            setQuick(less, pivot1);
                            ++less;
                        } else { // pivot1 < a[great] < pivot2
                            let(k, great);
                        }
                        setQuick(great, ak);
                        --great;
                    }
                }
            }

            // Sort center part recursively
            sort(less, great, false);

        } else { // Partitioning with one pivot
            /*
             * Use the third of the five sorted elements as pivot.
             * This value is inexpensive approximation of the median.
             */
            int pivot = getQuick(e3);

            /*
             * Partitioning degenerates to the traditional 3-way
             * (or "Dutch National Flag") schema:
             *
             *   left part    center part              right part
             * +-------------------------------------------------+
             * |  < pivot  |   == pivot   |     ?    |  > pivot  |
             * +-------------------------------------------------+
             *              ^              ^        ^
             *              |              |        |
             *             less            k      great
             *
             * Invariants:
             *
             *   all in (left, less)   < pivot
             *   all in [less, k)     == pivot
             *   all in (great, right) > pivot
             *
             * Pointer k is the first index of ?-part.
             */
            for (int k = less; k <= great; ++k) {
                if (getQuick(k) == pivot) {
                    continue;
                }
                int ak = getQuick(k);
                if (ak < pivot) { // Move a[k] to left part
                    let(k, less);
                    setQuick(less, ak);
                    ++less;
                } else { // a[k] > pivot - Move a[k] to right part
                    while (getQuick(great) > pivot) {
                        --great;
                    }
                    if (getQuick(great) < pivot) { // a[great] <= pivot
                        let(k, less);
                        let(less, great);
                        ++less;
                    } else { // a[great] == pivot
                        /*
                         * Even though a[great] equals to pivot, the
                         * assignment a[k] = pivot may be incorrect,
                         * if a[great] and pivot are floating-point
                         * zeros of different signs. Therefore in float
                         * and double sorting methods we have to use
                         * more accurate assignment a[k] = a[great].
                         */
                        setQuick(k, pivot);
                    }
                    setQuick(great, ak);
                    --great;
                }
            }

            /*
             * Sort left and right parts recursively.
             * All elements from center part are equal
             * and, therefore, already sorted.
             */
            sort(left, less - 1, leftmost);
            sort(great + 1, right, false);
        }
    }

    private void swap(int a, int b) {
        int tmp = getQuick(a);
        setQuick(a, getQuick(b));
        setQuick(b, tmp);
    }

    public boolean remove(int key) {
        for (int i = 0, n = size(); i < n; i++) {
            if (key == getQuick(i)) {
                removeIdx(i);
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public void removeIdx(int index) {
        if (pos < 1 || index >= pos) {
            return;
        }
        int move = pos - index - 1;
        if (move > 0) {
            System.arraycopy(buffer, index + 1, buffer, index, move);
        }
        Unsafe.arrayPut(buffer, --pos, noEntryValue);
    }

}