if (level == 0 &amp;&amp; (stored.quarters() &amp; 0xF) == 0xF) {
                cache.put(key, stored.hasCanopy() ? stored.expand(0, 0) : stored);
            }
...
            // Merged: a quarter built before may have spilled a crown into these columns (ADR-127).
            tile.mergeFeatureColumn(column % PreviewTile.FEATURE_SIDE, column / PreviewTile.FEATURE_SIDE,
                    runList.toIntArray());