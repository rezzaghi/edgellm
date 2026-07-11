# ServiceLoader discovery: the META-INF/services file is named by this
# interface's fully-qualified name, and implementations are instantiated
# reflectively — R8 sees no reference to either.
-keep interface io.github.rezzaghi.edgellm.engine.InferenceEngine
-keep class * implements io.github.rezzaghi.edgellm.engine.InferenceEngine {
    <init>();
}
