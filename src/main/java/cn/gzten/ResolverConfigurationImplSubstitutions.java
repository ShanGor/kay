package cn.gzten;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.InjectAccessors;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * @author Samuel Chan
 */
@TargetClass(jdk.internal.misc.PreviewFeatures.class)
final class PreviewFeaturesSubstitutions {

    @Alias
    @InjectAccessors(PreviewFeaturesEnabledAccessor.class)
    private static boolean ENABLED;

    private static class PreviewFeaturesEnabledAccessor {
        static boolean get() {
            return true;
        }
    }

    private static class ResolverConfigurationImplOsListAccessor {
        static String get() {
            return "";
        }

        static void set(String servers) {
        }
    }
}
