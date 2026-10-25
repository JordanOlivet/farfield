        // Single player builds the preview for its own client; a dedicated server for its players
        // when it streams it (ADR-068).
        StreamServer.onServerStarting(server);
        instance = new LodServer(!server.isDedicatedServer() || StreamServer.streamsPreview());

    out.write("# Build the preview of terrain not generated yet around players and send it to\n");
    out.write("# them (costs server CPU on low-priority threads). Default true.\n");
    out.write(PREVIEW + "=" + preview + "\n");