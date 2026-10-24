                addCommand(list, runStart, runEnd, r.slot);
        if (list != TRANSLUCENT) {
            // The plants' diagonals face every way: drawn whatever the camera's side.
            addCommand(list, r.groups[PLANT_GROUP], r.groups[PLANT_GROUP + 1], r.slot);
        }
    }