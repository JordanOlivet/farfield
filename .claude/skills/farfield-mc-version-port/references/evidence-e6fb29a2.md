> Task :common:26.3:compileJava FAILED
common\versions\26.3\build\generated\stonecutter\main\java\io\lakio\farfield\server\SandboxRegion.java:272: error: cannot find symbol
    public net.minecraft.world.level.BlockMatcher findBlocksIn(BlockPos from, BlockPos to) {
  symbol:   class BlockMatcher
BUILD FAILED in 52s
(grep of 26.3 WorldGenRegion.java) 44:import net.minecraft.world.level.blockscan.BlockMatcher;
273:    public BlockMatcher findBlocksIn(BlockPos from, BlockPos to) {
(after sed fix) BUILD SUCCESSFUL in 45s