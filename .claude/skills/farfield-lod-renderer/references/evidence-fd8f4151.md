                && (selectionTime == 0 || frameStart - selectionTime
                        >= Math.max(MIN_RESELECT_NANOS, SELECTION_SHARE * lastSelectNanos))) {
            long selectStart = System.nanoTime();
            select(level, cam, look, frameStart);
            lastSelectNanos = System.nanoTime() - selectStart;