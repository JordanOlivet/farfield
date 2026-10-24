        if (sections.ready(key)) {
            if (level > 0 && distance < detail * size) {
                fallbacks++;
            }
            draw.accept(key);
        } else {
            want.accept(key);
        }