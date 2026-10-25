$ which glslangValidator glslc 2>/dev/null; ls /c/VulkanSDK 2>/dev/null; ... pip show glslang 2>/dev/null | head -1
8.10
8.10.2
8.12
(find of ~/.gradle/caches for shaderc/glslang: no shaderc or glslang match; only org.lwjgl entries)
Assistant: Build OK. Les shaders ne se compilent qu'au lancement du jeu : il faut un client pour vérifier qu'ils compilent et mesurer le gain.
GlProgram.java: if (t.startsWith("#include \"") && t.endsWith("\"")) { out.append(read(t.substring("#include \"".length(), t.length() - 1))).append('\n'); }