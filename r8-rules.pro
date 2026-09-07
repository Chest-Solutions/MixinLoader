#noinspection ShrinkerUnresolvedReference

###############################################################################
# Global metadata and R8 operating mode
###############################################################################

# Signature is needed by Gson and generic reflection. AnnotationDefault is
# matched by *Annotation* and is required for annotation members such as
# @Shadow.prefix. Inner/nest metadata and parameter/exception metadata are
# consumed by the reflection-heavy libraries below.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,NestHost,NestMembers,MethodParameters,Exceptions

-dontobfuscate

# Keep R8 in shrink-only mode. The final Jar overlays the untouched
# MixinLoader class family after R8. Whole-program optimization can rewrite or
# remove dependency ABI members against R8's temporary caller bytecode; the
# overlay then restores calls to the original descriptors and causes linkage
# errors (for example AgentBuilder.Default.disableClassFormatChanges()). Tree
# shaking remains enabled, and the overlay is still required because R8 can
# regenerate inline-Advice bytecode even with optimization disabled.
-dontoptimize

###############################################################################
# Project bootstrap and Byte Buddy advice boundary
###############################################################################

# These remain R8 program roots for reachability analysis. The final Jar task
# must still overlay this family with the untouched javac output because R8
# cannot preserve inline Advice bytecode verbatim.
-keep class dev.csl.mixinloader.MixinLoader {
    *;
}
-keep class dev.csl.mixinloader.MixinLoader$** {
    *;
}

# JavaDispatcher reflects over proxy interfaces and nested annotations/support
# classes.
-keep class net.bytebuddy.utility.dispatcher.JavaDispatcher {
    *;
}
-keep class net.bytebuddy.utility.dispatcher.JavaDispatcher$* {
    *;
}
-keep interface net.bytebuddy.utility.dispatcher.JavaDispatcher$* {
    *;
}

-keep @net.bytebuddy.utility.dispatcher.JavaDispatcher$Proxied interface ** {
    *;
}
-keep @net.bytebuddy.utility.dispatcher.JavaDispatcher$Proxied class ** {
    *;
}

# Advice reads these annotations and blueprint methods from class files rather
# than invoking the blueprint methods normally.
-keep @interface net.bytebuddy.asm.Advice$* {
    *;
}
-keepclasseswithmembers class ** {
    #noinspection ShrinkerUnresolvedReference
    @net.bytebuddy.asm.Advice$OnMethodEnter <methods>;
}
-keepclasseswithmembers class ** {
    #noinspection ShrinkerUnresolvedReference
    @net.bytebuddy.asm.Advice$OnMethodExit <methods>;
}

###############################################################################
# JNA core, native mappings, and external OSHI/Paper boundary
###############################################################################

# JNA's JNI and reflection engine. Deliberately excludes com.sun.jna.platform
# as a package so unused platform mappings can still shrink.
-keep class com.sun.jna.* {
    *;
}
-keep interface com.sun.jna.* {
    *;
}
-keep class com.sun.jna.internal.** {
    *;
}
-keep interface com.sun.jna.internal.** {
    *;
}
-keep class com.sun.jna.ptr.** {
    *;
}
-keep interface com.sun.jna.ptr.** {
    *;
}
-keep class com.sun.jna.win32.** {
    *;
}
-keep interface com.sun.jna.win32.** {
    *;
}

# Native entry points and callback/mapping APIs are reached outside ordinary
# Java call-graph analysis.
-keepclasseswithmembernames,includedescriptorclasses class ** {
    native <methods>;
}
-keep,includedescriptorclasses interface ** extends com.sun.jna.Library {
    *;
}
-keep,includedescriptorclasses interface ** extends com.sun.jna.Callback {
    *;
}
# Structure mappings that survive shrinking are reflectively instantiated and
# may be subclassed by external host libraries such as OSHI. Prevent R8 from
# finalizing/merging those classes without forcing otherwise-unused mappings to
# remain in the artifact.
-keep,allowobfuscation,allowshrinking class ** extends com.sun.jna.Structure

# JNA creates NativeMapped values reflectively; external host code such as OSHI
# also calls their public wrapper APIs.
-keep class ** implements com.sun.jna.NativeMapped {
    public *;
}

# Required macOS mapping and its structure/layout types.
-keep,includedescriptorclasses interface com.sun.jna.platform.mac.SystemB {
    *;
}
-keep,includedescriptorclasses class com.sun.jna.platform.mac.SystemB$* {
    *;
}

###############################################################################
# ASM external API and reflective constant table
###############################################################################

# Paper and third-party mixin plugins link directly against the bundled ASM.
# Preserve the public/protected API, while allowing non-API implementation
# details to shrink. Keeping public interfaces also preserves Opcodes' named
# constants, which Mixin enumerates reflectively when detecting the ASM level.
-keep public class org.objectweb.asm.** {
    public *;
    protected *;
}
-keep public interface org.objectweb.asm.** {
    *;
}

###############################################################################
# Mixin and Mixin Extras annotation schemas
###############################################################################

# Third-party mixin bytecode consumes these annotation declarations, members,
# defaults, and meta-annotations. This keeps annotation types only, not either
# implementation library as a whole.
-keep @interface org.spongepowered.asm.mixin.** {
    *;
}
-keep @interface com.llamalad7.mixinextras.** {
    *;
}

###############################################################################
# Mixin Extras 0.5.4 reflection into Mixin 0.8.7
###############################################################################

-keep,includedescriptorclasses class org.spongepowered.asm.mixin.transformer.TargetClassContext {
    *** mixins;
}
-keep,includedescriptorclasses class org.spongepowered.asm.mixin.transformer.MixinInfo {
    *** getState();
}
-keep,includedescriptorclasses class org.spongepowered.asm.mixin.transformer.MixinInfo$State {
    *** classNode;
}
-keep class org.spongepowered.asm.mixin.transformer.ext.Extensions {
    *** extensions;
    *** activeExtensions;
}
-keep class org.spongepowered.asm.mixin.injection.struct.InjectionInfo {
    *** targetNodes;
    *** injector;
    *** registry;
    *** registeredAnnotations;
}
-keep class org.spongepowered.asm.mixin.injection.struct.InjectionNodes$InjectionNode {
    *** decorations;
}
-keep class org.spongepowered.asm.mixin.transformer.ClassInfo {
    #noinspection ShrinkerUnresolvedReference
    *** fromClassNode(org.objectweb.asm.tree.ClassNode);
}

# Loaded by name, instantiated, and inspected reflectively.
-keep,includedescriptorclasses class org.spongepowered.asm.mixin.injection.struct.InjectionInfo$InjectorEntry {
    <init>(java.lang.Class,java.lang.Class);
    *** annotationType;
}

-keep class org.spongepowered.asm.mixin.injection.InjectionPoint {
    *** types;
}

###############################################################################
# Mixin dynamic registries
###############################################################################

# InjectionInfo implementations are discovered from @AnnotationType and then
# instantiated/invoked dynamically.
-keep @org.spongepowered.asm.mixin.injection.struct.InjectionInfo$AnnotationType class ** extends org.spongepowered.asm.mixin.injection.struct.InjectionInfo {
    *;
}

# These are all of Mixin 0.8.7's built-in @AtCode holders in this package.
-keep class org.spongepowered.asm.mixin.injection.points.** {
    *;
}
-keep class org.spongepowered.asm.mixin.injection.modify.BeforeLoadLocal {
    *;
}
-keep class org.spongepowered.asm.mixin.injection.modify.AfterStoreLocal {
    *;
}

# Mixin Extras registers this injection point into InjectionPoint.types.
-keep class com.llamalad7.mixinextras.expression.impl.point.ExpressionInjectionPoint {
    *;
}

# TargetSelector registers this holder by annotation and finds both parse
# overloads reflectively.
-keep class org.spongepowered.asm.mixin.injection.selectors.dynamic.DynamicSelectorDesc {
    *;
}

# Loaded from Mixin's hard-coded platform-agent class name.
-keep class org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault {
    *;
}

###############################################################################
# Mixin configuration model populated by relocated Gson
###############################################################################

-keep @interface org.spongepowered.include.com.google.gson.annotations.SerializedName {
    *;
}
-keep class org.spongepowered.asm.mixin.transformer.MixinConfig {
    <init>();
    <fields>;
}
-keep class org.spongepowered.asm.mixin.transformer.MixinConfig$InjectorOptions {
    <init>();
    <fields>;
}
-keep class org.spongepowered.asm.mixin.transformer.MixinConfig$OverwriteOptions {
    <init>();
    <fields>;
}

###############################################################################
# Mixin Extras version and sugar factories
###############################################################################

# Selected with a dynamically assembled Class.forName name and a reflective
# public no-arg constructor.
-keep class com.llamalad7.mixinextras.versions.MixinVersionImpl* {
    *;
}

-keepclassmembers class com.llamalad7.mixinextras.sugar.impl.handlers.*HandlerTransformer {
    #noinspection ShrinkerUnresolvedReference
    <init>(org.spongepowered.asm.mixin.extensibility.IMixinInfo,com.llamalad7.mixinextras.sugar.impl.SugarParameter);
}
-keepclassmembers class com.llamalad7.mixinextras.sugar.impl.*SugarApplicator {
    #noinspection ShrinkerUnresolvedReference
    <init>(org.spongepowered.asm.mixin.injection.struct.InjectionInfo,com.llamalad7.mixinextras.sugar.impl.SugarParameter);
}

###############################################################################
# APIs and helpers referenced only by post-R8 generated/transformed bytecode
###############################################################################

-keep interface org.spongepowered.asm.mixin.injection.callback.Cancellable {
    *;
}
-keep class org.spongepowered.asm.mixin.injection.callback.CallbackInfo {
    *;
}
-keep class org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable {
    *;
}

# Runtime-generated Args subclasses extend and invoke this base ABI.
-keep class org.spongepowered.asm.mixin.injection.invoke.arg.Args {
    *;
}

# Third-party handlers and generated local-reference implementations use these
# interfaces after R8 has finished its analysis.
-keep interface com.llamalad7.mixinextras.sugar.ref.** {
    *;
}
-keep class com.llamalad7.mixinextras.sugar.impl.ref.LocalRefRuntime {
    public static *;
}

# Operation lambdas/bridges and expression transformations generate calls to
# these members after R8 analysis.
-keep interface com.llamalad7.mixinextras.injector.wrapoperation.Operation {
    *;
}
-keep class com.llamalad7.mixinextras.injector.wrapoperation.WrapOperationRuntime {
    public static *;
}
-keep class com.llamalad7.mixinextras.injector.MixinExtrasHooks {
    public static *;
}

###############################################################################
# Intentionally absent optional/compile-time dependencies
###############################################################################

-dontwarn org.spongepowered.include.com.google.errorprone.annotations.**
-dontwarn org.spongepowered.include.com.google.j2objc.annotations.**
-dontwarn org.jetbrains.java.decompiler.**
-dontwarn net.minecraft.launchwrapper.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.google.auto.service.**
-dontwarn org.checkerframework.**
-dontwarn io.papermc.paperclip.**
-dontwarn cpw.mods.modlauncher.**
-dontwarn edu.umd.cs.findbugs.**
-dontwarn org.yaml.snakeyaml.**
-dontwarn io.papermc.paper.**
-dontwarn com.google.gson.**
-dontwarn joptsimple.**
-dontwarn org.bukkit.**
-dontwarn javax.**
