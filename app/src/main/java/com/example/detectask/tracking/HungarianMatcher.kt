package com.example.detectask.tracking

/**
 * Implements the Hungarian algorithm for solving the assignment problem.
 *
 * Takes a cost matrix and returns the optimal one-to-one assignment that minimizes total cost.
 */
object HungarianMatcher {

    /**
     * Computes the optimal assignment for a given cost matrix.
     *
     * @param costMatrix A rectangular [Array] of [FloatArray] representing costs for each possible assignment.
     * @return A [Map] from row indices to assigned column indices.
     *
     * If the input matrix is not square, it is padded with large dummy costs.
     */
    fun match(costMatrix: Array<FloatArray>): Map<Int, Int> {
        if (costMatrix.isEmpty() || costMatrix[0].isEmpty()) return emptyMap()

        val numRows = costMatrix.size
        val numCols = costMatrix[0].size
        val dim = maxOf(numRows, numCols)

        // Pad cost matrix to square with high values
        val matrix = Array(dim) { i ->
            FloatArray(dim) { j ->
                if (i < numRows && j < numCols) costMatrix[i][j] else 1e6f
            }
        }

        val mask = Array(dim) { IntArray(dim) }
        val rowCover = BooleanArray(dim)
        val colCover = BooleanArray(dim)
        var path = Array(dim * 2) { IntArray(2) }
        var step = 1
        var z0r = 0
        var z0c = 0

        fun findZero(): Pair<Int, Int>? {
            for (i in 0 until dim) {
                if (!rowCover[i]) {
                    for (j in 0 until dim) {
                        if (matrix[i][j] == 0f && !colCover[j]) {
                            return i to j
                        }
                    }
                }
            }
            return null
        }

        fun findStarInRow(row: Int): Int = mask[row].indexOfFirst { it == 1 }
        fun findStarInCol(col: Int): Int = mask.indexOfFirst { it[col] == 1 }
        fun findPrimeInRow(row: Int): Int = mask[row].indexOfFirst { it == 2 }

        // Main algorithm loop
        while (true) {
            when (step) {
                1 -> {
                    for (i in 0 until dim) {
                        val minVal = matrix[i].minOrNull() ?: continue
                        for (j in 0 until dim) matrix[i][j] -= minVal
                    }
                    step = 2
                }
                2 -> {
                    for (i in 0 until dim) {
                        for (j in 0 until dim) {
                            if (matrix[i][j] == 0f && !rowCover[i] && !colCover[j]) {
                                mask[i][j] = 1
                                rowCover[i] = true
                                colCover[j] = true
                            }
                        }
                    }
                    rowCover.fill(false)
                    colCover.fill(false)
                    step = 3
                }
                3 -> {
                    for (i in 0 until dim) {
                        for (j in 0 until dim) {
                            if (mask[i][j] == 1) colCover[j] = true
                        }
                    }
                    step = if (colCover.count { it } >= dim) 7 else 4
                }
                4 -> {
                    var done = false
                    while (!done) {
                        val zero = findZero()
                        if (zero == null) {
                            step = 6
                            done = true
                        } else {
                            val (i, j) = zero
                            mask[i][j] = 2
                            val starCol = findStarInRow(i)
                            if (starCol != -1) {
                                rowCover[i] = true
                                colCover[starCol] = false
                            } else {
                                step = 5
                                z0r = i
                                z0c = j
                                done = true
                            }
                        }
                    }
                }
                5 -> {
                    var count = 0
                    path[count][0] = z0r
                    path[count][1] = z0c
                    var done = false
                    while (!done) {
                        val row = findStarInCol(path[count][1])
                        if (row != -1) {
                            count++
                            path[count][0] = row
                            path[count][1] = path[count - 1][1]
                        } else {
                            done = true
                            break
                        }
                        val col = findPrimeInRow(path[count][0])
                        count++
                        path[count][0] = path[count - 1][0]
                        path[count][1] = col
                    }
                    for (i in 0..count) {
                        mask[path[i][0]][path[i][1]] = when (mask[path[i][0]][path[i][1]]) {
                            1 -> 0
                            2 -> 1
                            else -> mask[path[i][0]][path[i][1]]
                        }
                    }
                    rowCover.fill(false)
                    colCover.fill(false)
                    for (i in 0 until dim) {
                        for (j in 0 until dim) {
                            if (mask[i][j] == 2) mask[i][j] = 0
                        }
                    }
                    step = 3
                }
                6 -> {
                    val minVal = matrix.indices
                        .filter { !rowCover[it] }
                        .flatMap { i -> matrix[i].indices.filter { !colCover[it] }.map { j -> matrix[i][j] } }
                        .minOrNull() ?: 0f
                    for (i in 0 until dim) {
                        for (j in 0 until dim) {
                            if (rowCover[i]) matrix[i][j] += minVal
                            if (!colCover[j]) matrix[i][j] -= minVal
                        }
                    }
                    step = 4
                }
                7 -> break
            }
        }

        // Extract assignments from the mask
        val result = mutableMapOf<Int, Int>()
        for (i in 0 until numRows) {
            for (j in 0 until numCols) {
                if (mask[i][j] == 1) result[i] = j
            }
        }
        return result
    }
}
