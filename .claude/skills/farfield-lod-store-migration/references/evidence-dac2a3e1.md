Un bug grave : `LodWorld` efface à chaque ouverture le dossier `sections/L8`, alors que L8 est désormais un vrai niveau ; je ne garderai ce nettoyage que pour les anciens stores sans `keys.version`.

// Only in a store of the keys before ADR-138: since, L8 holds the real level 8.
if (!Files.exists(dir.resolve("sections").resolve("keys.version"))) {
    deleteOldPreview(dir.resolve("sections").resolve("L8"));
}