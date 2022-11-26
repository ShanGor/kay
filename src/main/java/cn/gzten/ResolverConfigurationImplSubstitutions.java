package cn.gzten;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.InjectAccessors;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * @author Samuel Chan
 */
//@TargetClass(sun.net.dns.ResolverConfigurationImpl.class)
final class ResolverConfigurationImplSubstitutions {
//    @Alias
//    @InjectAccessors(ResolverConfigurationImplDnsListAccessor.class)
    private static String os_nameservers;
//    @Alias
//    @InjectAccessors(ResolverConfigurationImplOsListAccessor.class)
    private static String os_searchlist;

    private static class ResolverConfigurationImplDnsListAccessor {
        static String get() {
            return "";
        }

        static void set(String servers) {
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
