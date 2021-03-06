package snownee.kiwi;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.objectweb.asm.Type;

import com.electronwill.nightconfig.core.utils.StringUtils;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;

import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.event.server.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.moddiscovery.ModAnnotation;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.fml.loading.toposort.TopologicalSort;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.ModFileScanData.AnnotationData;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;
import net.minecraftforge.registries.ObjectHolderRegistry;
import net.minecraftforge.registries.RegistryManager;
import snownee.kiwi.KiwiModule.Group;
import snownee.kiwi.KiwiModule.Subscriber;
import snownee.kiwi.command.KiwiCommand;
import snownee.kiwi.config.ConfigHandler;
import snownee.kiwi.config.KiwiConfig;
import snownee.kiwi.config.KiwiConfigManager;
import snownee.kiwi.crafting.FullBlockIngredient;
import snownee.kiwi.crafting.ModuleLoadedCondition;
import snownee.kiwi.schedule.Scheduler;
import snownee.kiwi.util.ReflectionUtil;
import snownee.kiwi.util.Util;

@Mod(Kiwi.MODID)
@EventBusSubscriber
public class Kiwi {
    public static final String MODID = "kiwi";
    public static final String NAME = "Kiwi";

    public static Logger logger = LogManager.getLogger(Kiwi.NAME);
    static final Marker MARKER = MarkerManager.getMarker("Init");
    static Field FIELD_EXTENSION;

    private static final class Info {
        final ResourceLocation id;
        final String className;
        final List<ResourceLocation> moduleRules = Lists.newLinkedList();

        public Info(ResourceLocation id, String className) {
            this.id = id;
            this.className = className;
        }
    }

    static {
        try {
            FIELD_EXTENSION = ModContainer.class.getDeclaredField("contextExtension");
            FIELD_EXTENSION.setAccessible(true);
        } catch (NoSuchFieldException | SecurityException e) {
            Kiwi.logger.catching(e);
        }
    }

    private static Multimap<String, AnnotationData> moduleData = ArrayListMultimap.create();
    public static Map<ResourceLocation, Boolean> defaultOptions = Maps.newHashMap();
    private static SetMultimap<ResourceLocation, KiwiObjectHolderRef> holderRefs = HashMultimap.create();
    private static Map<AnnotationData, String> conditions = Maps.newHashMap();

    @SuppressWarnings("unused")
    public Kiwi() throws Exception {
        final Type KIWI_MODULE = Type.getType(KiwiModule.class);
        final Type KIWI_CONFIG = Type.getType(KiwiConfig.class);
        final Type OPTIONAL_MODULE = Type.getType(KiwiModule.Optional.class);
        final Type LOADING_CONDITION = Type.getType(KiwiModule.LoadingCondition.class);

        Map<Type, AnnotationData> moduleToOptional = Maps.newHashMap();
        for (ModInfo info : ModList.get().getMods()) {
            ModFileInfo modFileInfo = info.getOwningFile();
            if (modFileInfo != null) {
                for (AnnotationData annotationData : modFileInfo.getFile().getScanResult().getAnnotations()) {
                    if (KIWI_MODULE.equals(annotationData.getAnnotationType())) {
                        String modid = (String) annotationData.getAnnotationData().get("modid");
                        moduleData.put(Strings.isNullOrEmpty(modid) ? info.getModId() : modid, annotationData);
                    } else if (KIWI_CONFIG.equals(annotationData.getAnnotationType())) {
                        ModAnnotation.EnumHolder typeHolder = (ModAnnotation.EnumHolder) annotationData.getAnnotationData().get("type");
                        ModConfig.Type type = null;
                        if (typeHolder != null) {
                            type = ModConfig.Type.valueOf(typeHolder.getValue());
                        }
                        type = type == null ? ModConfig.Type.COMMON : type;
                        if (!(type == ModConfig.Type.CLIENT && FMLEnvironment.dist.isDedicatedServer())) {
                            try {
                                Class<?> clazz = Class.forName(annotationData.getClassType().getClassName());
                                KiwiConfig kiwiConfig = clazz.getAnnotation(KiwiConfig.class);
                                String fileName = kiwiConfig.value();
                                boolean master = type == ModConfig.Type.COMMON && Strings.isNullOrEmpty(fileName);
                                if (Strings.isNullOrEmpty(fileName)) {
                                    fileName = String.format("%s-%s", info.getModId(), type.extension());
                                }
                                new ConfigHandler(info.getModId(), fileName + ".toml", type, clazz, master);
                            } catch (ClassNotFoundException e) {
                                logger.catching(e);
                            }
                        }
                    } else if (OPTIONAL_MODULE.equals(annotationData.getAnnotationType())) {
                        moduleToOptional.put(annotationData.getClassType(), annotationData);
                    } else if (LOADING_CONDITION.equals(annotationData.getAnnotationType())) {
                        conditions.put(annotationData, info.getModId());
                    }
                }
            }
        }

        logger.info(MARKER, "Processing " + moduleData.size() + " KiwiModule annotations");

        for (Entry<String, AnnotationData> entry : moduleData.entries()) {
            AnnotationData optional = moduleToOptional.get(entry.getValue().getClassType());
            if (optional != null) {
                String modid = entry.getKey();
                if (!ModList.get().isLoaded(modid)) {
                    continue;
                }

                String name = (String) entry.getValue().getAnnotationData().get("value");
                if (Strings.isNullOrEmpty(name)) {
                    name = "core";
                }

                Boolean disabledByDefault = (Boolean) optional.getAnnotationData().get("disabledByDefault");
                if (disabledByDefault == null) {
                    disabledByDefault = Boolean.FALSE;
                }
                defaultOptions.put(new ResourceLocation(modid, name), disabledByDefault);
            }
        }

        KiwiConfigManager.init();
        final IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(EventPriority.LOWEST, this::preInit);
        modEventBus.addListener(this::init);
        modEventBus.addListener(this::clientInit);
        MinecraftForge.EVENT_BUS.addListener(this::serverInit);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, this::serverStarting);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, this::serverStopped);
        modEventBus.addListener(this::postInit);
        modEventBus.addListener(this::loadComplete);
        try {
            Method method = modEventBus.getClass().getDeclaredMethod("addListener", EventPriority.class, Predicate.class, Consumer.class);
            method.setAccessible(true);
            method.invoke(modEventBus, EventPriority.NORMAL, Predicates.alwaysTrue(), (Consumer<RegistryEvent.Register<?>>) KiwiManager::handleRegister);
        } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            logger.fatal("Kiwi failed to start up. Please report this to developer!");
            throw e;
        }

        //LootConditionManager.registerCondition(new HasLootTable.Serializer());
    }

    private void preInit(RegistryEvent.NewRegistry event) {
        try {
            KiwiConfigManager.preload();
        } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
            logger.fatal(MARKER, "Kiwi failed to start up. Please report this to developer!");
            logger.catching(e);
            return;
        }

        Set<ResourceLocation> disabledModules = Sets.newHashSet();
        conditions.forEach((k, v) -> {
            try {
                Class<?> clazz = Class.forName(k.getClassType().getClassName());
                int p = k.getMemberName().indexOf('(');
                if (p <= 0) {
                    throw new IllegalArgumentException();
                }
                String methodName = k.getMemberName().substring(0, p);
                List<String> values = (List<String>) k.getAnnotationData().get("value");
                if (values == null) {
                    values = Arrays.asList(v);
                }
                List<ResourceLocation> ids = values.stream().map(s -> checkPrefix(s, v)).collect(Collectors.toList());
                for (ResourceLocation id : ids) {
                    LoadingContext context = new LoadingContext(id);
                    try {
                        Boolean bl = (Boolean) MethodUtils.invokeExactStaticMethod(clazz, methodName, context);
                        if (!bl) {
                            disabledModules.add(id);
                        }
                    } catch (Exception e) {
                        disabledModules.add(id);
                        throw e;
                    }
                }
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
                logger.error(MARKER, "Failed to access to LoadingCondition: {}", k);
                logger.catching(e);
            }
        });

        final Map<ResourceLocation, Info> infos = Maps.newHashMap();
        boolean checkDep = false;

        load:
        for (Entry<String, AnnotationData> entry : moduleData.entries()) {
            AnnotationData module = entry.getValue();
            String modid = entry.getKey();
            if (!ModList.get().isLoaded(modid)) {
                continue;
            }

            String name = (String) module.getAnnotationData().get("value");
            if (Strings.isNullOrEmpty(name)) {
                name = "core";
            }

            ResourceLocation rl = new ResourceLocation(modid, name);
            if (disabledModules.contains(rl)) {
                if (KiwiConfigManager.modules.containsKey(rl)) { // module is optional
                    continue;
                } else {
                    throw new RuntimeException("Cannot load mandatory module: " + rl);
                }
            }
            if (KiwiConfigManager.modules.containsKey(rl) && !KiwiConfigManager.modules.get(rl).get()) {
                continue;
            }

            Info info = new Info(rl, module.getClassType().getClassName());

            String dependencies = (String) module.getAnnotationData().get("dependencies");
            /* off */
            List<String> rules = StringUtils.split(Strings.nullToEmpty(dependencies), ';').stream()
                    .filter(s -> !Strings.isNullOrEmpty(s))
                    .collect(Collectors.toList());
            /* on */

            for (String rule : rules) {
                if (rule.startsWith("@")) {
                    info.moduleRules.add(Util.RL(rule.substring(1), modid));
                    checkDep = true;
                } else if (!ModList.get().isLoaded(rule)) {
                    continue load;
                }
            }
            infos.put(rl, info);
        }

        List<ResourceLocation> list = null;
        if (checkDep) {
            List<Info> errorList = Lists.newLinkedList();
            for (Info i : infos.values()) {
                for (ResourceLocation id : i.moduleRules) {
                    if (!infos.containsKey(id)) {
                        errorList.add(i);
                        break;
                    }
                }
            }
            for (Info i : errorList) {
                IModInfo modInfo = ModList.get().getModContainerById(i.id.getNamespace()).get().getModInfo();
                String dependencies = org.apache.commons.lang3.StringUtils.join(i.moduleRules, ", ");
                ModLoader.get().addWarning(new ModLoadingWarning(modInfo, ModLoadingStage.ERROR, "msg.kiwi.no_dependencies", i.id, dependencies));
            }
            if (!errorList.isEmpty()) {
                return;
            }
            MutableGraph<ResourceLocation> graph = GraphBuilder.directed().allowsSelfLoops(false).expectedNodeCount(infos.size()).build();
            infos.keySet().forEach(graph::addNode);
            infos.values().forEach($ -> {
                $.moduleRules.forEach(r -> graph.putEdge(r, $.id));
            });
            list = TopologicalSort.topologicalSort(graph, null);
        } else {
            list = ImmutableList.copyOf(infos.keySet());
        }

        for (ResourceLocation id : list) {
            Info info = infos.get(id);
            ModContext context = ModContext.get(id.getNamespace());
            context.setActiveContainer();

            // Instantiate modules
            try {
                Class<?> clazz = Class.forName(info.className);
                AbstractModule instance = (AbstractModule) clazz.newInstance();
                KiwiManager.addInstance(id, instance, context);
            } catch (InstantiationException | IllegalAccessException | ClassCastException | ClassNotFoundException e) {
                logger.error(MARKER, "Kiwi failed to initialize module class: {}", info.className);
                logger.catching(e);
                continue;
            }

            ModLoadingContext.get().setActiveContainer(null, null);
        }

        moduleData.clear();
        moduleData = null;
        defaultOptions.clear();
        defaultOptions = null;
        conditions.clear();
        conditions = null;

        Util.class.hashCode();
        Object2IntMap<Class<?>> counter = new Object2IntArrayMap<>();
        for (ModuleInfo info : KiwiManager.MODULES.values()) {
            counter.clear();
            info.context.setActiveContainer();
            Subscriber subscriber = info.module.getClass().getAnnotation(Subscriber.class);
            if (subscriber != null && ArrayUtils.contains(subscriber.side(), FMLEnvironment.dist)) {
                // processEvents(info.module);
                subscriber.value().bus().get().register(info.module);
            }

            boolean useOwnGroup = info.group == null;
            if (useOwnGroup) {
                Group group = info.module.getClass().getAnnotation(Group.class);
                if (group != null) {
                    String val = group.value();
                    if (!val.isEmpty()) {
                        useOwnGroup = false;
                        ItemGroup itemGroup = getGroup(val);
                        if (itemGroup != null) {
                            info.group = itemGroup;
                        }
                    }
                }
            }

            String modid = info.module.uid.getNamespace();
            String name = info.module.uid.getPath();

            Item.Properties tmpBuilder = null;
            Field tmpBuilderField = null;
            for (Field field : info.module.getClass().getFields()) {
                if (field.getAnnotation(Skip.class) != null) {
                    continue;
                }

                int mods = field.getModifiers();
                if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods)) {
                    continue;
                }

                ResourceLocation regName;
                Name nameAnnotation = field.getAnnotation(Name.class);
                if (nameAnnotation != null) {
                    regName = checkPrefix(nameAnnotation.value(), modid);
                } else {
                    regName = checkPrefix(field.getName().toLowerCase(Locale.ENGLISH), modid);
                }

                if (!Modifier.isFinal(mods)) {
                    if (field.getType() == info.module.getClass() && regName.getPath().equals("instance") && regName.getNamespace().equals(modid)) {
                        try {
                            field.set(null, info.module);
                        } catch (IllegalArgumentException | IllegalAccessException e) {
                            logger.error(MARKER, "Kiwi failed to inject module instance to module class: {}", info.module.uid);
                            logger.catching(e);
                        }
                    }
                    continue;
                }

                Object o = null;
                try {
                    o = field.get(null);
                } catch (IllegalArgumentException | IllegalAccessException e) {
                    logger.error(MARKER, "Kiwi failed to catch game object: {}", field);
                    logger.catching(e);
                }
                if (o == null) {
                    continue;
                }
                if (useOwnGroup && info.group == null && o instanceof ItemGroup) {
                    info.group = (ItemGroup) o;
                } else if (o instanceof IRecipeType) {
                    Registry.register(Registry.RECIPE_TYPE, regName, (IRecipeType<?>) o);
                    tmpBuilder = null;
                    tmpBuilderField = null;
                    continue;
                } else if (o instanceof Item.Properties) {
                    tmpBuilder = (Item.Properties) o;
                    tmpBuilderField = field;
                    continue;
                } else if (o instanceof Block) {
                    if (field.getAnnotation(NoItem.class) != null) {
                        info.noItems.add((Block) o);
                    }
                    checkNoGroup(info, field, o);
                    if (tmpBuilder != null) {
                        info.blockItemBuilders.put((Block) o, tmpBuilder);
                        try {
                            ReflectionUtil.setFinalValue(tmpBuilderField, info.module, null);
                        } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
                            logger.error(MARKER, "Kiwi failed to clean used item builder: {}", tmpBuilderField);
                            logger.catching(e);
                        }
                    }
                } else if (o instanceof Item) {
                    checkNoGroup(info, field, o);
                }
                if (o instanceof IForgeRegistryEntry) {
                    IForgeRegistryEntry<?> entry = (IForgeRegistryEntry<?>) o;
                    Class<?> superType = entry.getRegistryType();
                    int i = counter.getOrDefault(superType, 0);
                    counter.put(superType, i + 1);
                    info.register(entry, regName, field);
                    Optional<KiwiObjectHolderRef> optional = holderRefs.get(regName).stream().filter(ref -> superType.equals(ref.getRegistryType())).findAny();
                    if (optional.isPresent()) {
                        ObjectHolderRegistry.addHandler(optional.get().withField(field));
                    }
                }

                tmpBuilder = null;
                tmpBuilderField = null;
            }

            logger.info(MARKER, "Module [{}:{}] initialized", modid, name);
            for (Class<?> clazz : counter.keySet()) {
                IForgeRegistry<?> registry = RegistryManager.ACTIVE.getRegistry((Class<IForgeRegistryEntry<?>>) clazz);
                String k;
                if (registry != null) {
                    k = Util.trimRL(registry.getRegistryName());
                } else {
                    k = "unknown";
                }
                logger.info(MARKER, "    {}: {}", k, counter.getInt(clazz));
            }
        }

        KiwiManager.MODULES.values().forEach(ModuleInfo::preInit);
        ModLoadingContext.get().setActiveContainer(null, null);
        holderRefs.clear();
        holderRefs = null;
    }

    //    private static void processEvents(Object object) {
    //        for (Method method : object.getClass().getMethods()) {
    //            
    //        }
    //        //IModBusEvent
    //    }

    private static Map<String, ItemGroup> GROUP_CACHE = Maps.newHashMap();

    static ItemGroup getGroup(String path) {
        if (GROUP_CACHE == null) {
            return null;
        }
        return GROUP_CACHE.computeIfAbsent(path, $ -> {
            for (ItemGroup group : ItemGroup.GROUPS) {
                if (path.equals(group.getPath())) {
                    return group;
                }
            }
            return null;
        });
    }

    private static void checkNoGroup(ModuleInfo info, Field field, Object o) {
        if (field.getAnnotation(NoGroup.class) != null) {
            info.noGroups.add(o);
        }
    }

    private void init(FMLCommonSetupEvent event) {
        KiwiConfigManager.refresh();
        CraftingHelper.register(new ModuleLoadedCondition.Serializer());
        CraftingHelper.register(new ResourceLocation(MODID, "full_block"), FullBlockIngredient.SERIALIZER);

        KiwiManager.MODULES.values().forEach(m -> m.init(event));
        ModLoadingContext.get().setActiveContainer(null, null);
    }

    private void clientInit(FMLClientSetupEvent event) {
        KiwiManager.MODULES.values().forEach(m -> m.clientInit(event));
        ModLoadingContext.get().setActiveContainer(null, null);
    }

    private void serverInit(FMLServerStartingEvent event) {
        KiwiManager.MODULES.values().forEach(m -> m.serverInit(event));
        event.getServer().getWorld(World.OVERWORLD).getSavedData().getOrCreate(() -> Scheduler.INSTANCE, Scheduler.ID);
        ModLoadingContext.get().setActiveContainer(null, null);
    }

    @SubscribeEvent
    protected static void onCommandsRegister(RegisterCommandsEvent event) {
        KiwiCommand.register(event.getDispatcher(), event.getEnvironment());
    }

    private static MinecraftServer server;

    public static MinecraftServer getServer() {
        return server;
    }

    private void serverStarting(FMLServerAboutToStartEvent event) {
        server = event.getServer();
    }

    private void serverStopped(FMLServerStoppedEvent event) {
        server = null;
    }

    private void postInit(InterModProcessEvent event) {
        KiwiManager.MODULES.values().forEach(ModuleInfo::postInit);
        ModLoadingContext.get().setActiveContainer(null, null);
    }

    private void loadComplete(FMLLoadCompleteEvent event) {
        GROUP_CACHE.clear();
        GROUP_CACHE = null;
    }

    public static boolean isLoaded(ResourceLocation module) {
        return KiwiManager.MODULES.containsKey(module);
    }

    public static boolean isLoaded(AbstractModule module) {
        return KiwiManager.MODULES.values().stream().map($ -> $.module).anyMatch(module::equals);
    }

    /**
     * @since 2.6.0
     */
    public static void applyObjectHolder(IForgeRegistry<?> registry, ResourceLocation registryName) {
        if (holderRefs == null) {
            logger.warn(MARKER, "Adding object holder too late. {}: {}", registry, registryName);
            return;
        }
        holderRefs.put(registryName, new KiwiObjectHolderRef(null, registryName, registry));
    }

    private static ResourceLocation checkPrefix(String name, String defaultModid) {
        if (name.contains(":")) {
            return new ResourceLocation(name);
        } else {
            return new ResourceLocation(defaultModid, name);
        }
    }

    private static boolean tagsUpdated;

    @SubscribeEvent
    protected static void onTagsUpdated(TagsUpdatedEvent event) {
        tagsUpdated = true;
    }

    /**
     * @since 3.1.3
     */
    public static boolean areTagsUpdated() {
        return tagsUpdated;
    }

}
