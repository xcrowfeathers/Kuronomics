package de.kuro.kuronomics.block;

import de.kuro.kuronomics.registry.ModContent;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class MachineBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty ACTIVE = IntegerProperty.create("blockstate", 0, 1);
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    private static final java.util.Map<Direction, VoxelShape> ATM_SHAPES = rotated(Shapes.or(
            box(0, 0, 0, 16, 16, 16), box(14, 16, 1, 16, 32, 16), box(0, 16, 1, 2, 32, 16),
            box(2, 12.33948, 1.26551, 14, 19.33948, 9.26551), box(2, 16, 6, 14, 27, 16), box(2, 27, 3, 14, 32, 16)));
    private static final java.util.Map<Direction, VoxelShape> READER_SHAPES = rotated(box(0, 0, 1, 16, 16, 16));
    private static java.util.Map<Direction, VoxelShape> rotated(VoxelShape north) {
        var shapes = new java.util.EnumMap<Direction, VoxelShape>(Direction.class); VoxelShape shape = north;
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            shapes.put(direction, shape);
            VoxelShape[] next = {Shapes.empty()};
            shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> next[0] = Shapes.or(next[0], Shapes.box(1 - z2, y1, x1, 1 - z1, y2, x2)));
            shape = next[0];
        }
        return shapes;
    }
    public final MachineKind kind;
    public MachineBlock(MachineKind kind) {
        super(properties(kind));
        this.kind = kind; registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, 0).setValue(FACE, AttachFace.FLOOR));
    }
    private static Properties properties(MachineKind kind) {
        Properties p = Properties.of().noOcclusion().isRedstoneConductor((state, level, pos) -> false);
        if (kind.shop() || kind.vending()) return p.strength(-1, 3600000).sound(kind.shop() ? SoundType.WOOD : SoundType.METAL);
        return switch (kind) {
            case ATM -> p.strength(30, 10).sound(SoundType.ANVIL).requiresCorrectToolForDrops();
            case PASS_READER -> p.strength(1, 10).sound(SoundType.BONE_BLOCK);
            case ID_CHECKER -> p.strength(80, 100).sound(SoundType.METAL).requiresCorrectToolForDrops();
            default -> p.strength(10, 30).sound(SoundType.METAL);
        };
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return MapCodec.unit(this); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, ACTIVE, FACE); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (kind == MachineKind.COMPANY_SHOP && context.getPlayer() instanceof ServerPlayer player
                && de.kuro.kuronomics.market.MarketPermissions.isEmployee(player)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("Employees can stock company shops, but cannot place them."), true); return null;
        }
        AttachFace face = context.getClickedFace() == Direction.DOWN ? AttachFace.CEILING : context.getClickedFace() == Direction.UP ? AttachFace.FLOOR : AttachFace.WALL;
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(FACE, kind == MachineKind.PRINTER ? face : AttachFace.FLOOR);
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return rotate(state, mirror.getRotation(state.getValue(FACING))); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MachineBlockEntity(kind, pos, state); }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity entity, ItemStack stack) {
        if (!level.isClientSide && entity != null && level.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
            machine.owner(entity.getUUID());
            if (kind == MachineKind.COMPANY_SHOP && entity instanceof ServerPlayer player)
                de.kuro.kuronomics.menu.CompanyShopMenu.assignOrOpen(player, machine);
        }
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
            if (kind == MachineKind.COMPANY_SHOP && !machine.assigned()) {
                if (player.isShiftKeyDown() && machine.canRemove(player)) { level.destroyBlock(pos, true, player); return InteractionResult.CONSUME; }
                de.kuro.kuronomics.menu.CompanyShopMenu.assignOrOpen(serverPlayer, machine); return InteractionResult.CONSUME;
            }
            if (kind == MachineKind.ID_CHECKER) {
                if (player.isShiftKeyDown() && machine.canManage(player)) {
                    machine.cycleCard(); int color = machine.cardColor();
                    player.displayClientMessage(de.kuro.kuronomics.util.ModText.legacy("Now accepts "
                            + new String[]{"§bblue", "§dpurple", "§cred", "§agreen"}[color] + " §rid cards"), true);
                }
                else if (state.getValue(ACTIVE) == 0 && player.getMainHandItem().is(ModContent.DOCUMENTS.get(machine.cardColor()).get())) {
                    level.setBlock(pos, state.setValue(ACTIVE, 1), 3); level.updateNeighborsAt(pos, this);
                    level.scheduleTick(pos, this, machine.cardColor() == 0 ? 40 : 60);
                    ModContent.play(level, pos, "id_accept");
                } else if (state.getValue(ACTIVE) == 0) {
                    ModContent.play(level, pos, "id_incorrect");
                }
            } else {
                if (kind == MachineKind.ATM) ModContent.play(level, pos, "atm_boot");
                de.kuro.kuronomics.menu.MachineMenu.open(serverPlayer, machine, 0);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (kind == MachineKind.ID_CHECKER) { level.setBlock(pos, state.setValue(ACTIVE, 0), 3); level.updateNeighborsAt(pos, this); }
    }
    @Override protected boolean isSignalSource(BlockState state) { return kind == MachineKind.ID_CHECKER; }
    @Override protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) { return kind == MachineKind.ID_CHECKER && state.getValue(ACTIVE) == 1 ? 15 : 0; }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level.getBlockEntity(pos) instanceof MachineBlockEntity machine) Containers.dropContents(level, pos, machine);
        super.onRemove(state, level, pos, next, moving);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (kind == MachineKind.ATM) return ATM_SHAPES.get(state.getValue(FACING));
        if (kind == MachineKind.PASS_READER) return READER_SHAPES.get(state.getValue(FACING));
        return kind.vending() ? Block.box(0, 0, 0, 16, 32, 16) : Shapes.block();
    }
}
