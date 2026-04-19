package wings.v.core;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import wings.v.R;

public final class XposedAttackVector {

    public static final String NETWORK_CAPS_HAS_TRANSPORT_VPN = "network_caps_has_transport_vpn";
    public static final String NETWORK_CAPS_TRANSPORT_INFO = "network_caps_transport_info";
    public static final String LINK_PROPERTIES_INTERFACE_NAME = "link_properties_interface_name";
    public static final String LINK_PROPERTIES_ALL_INTERFACES = "link_properties_all_interfaces";
    public static final String LINK_PROPERTIES_ROUTES = "link_properties_routes";
    public static final String LINK_PROPERTIES_DNS = "link_properties_dns";
    public static final String PROCFS_JAVA = "procfs_java";
    public static final String SYSTEM_PROXY_PROPERTIES = "system_proxy_properties";
    public static final String NETWORK_INTERFACE_LIST = "network_interface_list";
    public static final String NETWORK_INTERFACE_BY_NAME = "network_interface_by_name";
    public static final String PACKAGE_MANAGER_INSTALLED_PACKAGES = "package_manager_installed_packages";
    public static final String PACKAGE_MANAGER_INSTALLED_APPLICATIONS = "package_manager_installed_applications";
    public static final String PACKAGE_MANAGER_PACKAGE_INFO = "package_manager_package_info";
    public static final String PACKAGE_MANAGER_APPLICATION_INFO = "package_manager_application_info";
    public static final String PACKAGE_MANAGER_QUERY_INTENT_SERVICES = "package_manager_query_intent_services";
    public static final String PACKAGE_MANAGER_RESOLVE_SERVICE = "package_manager_resolve_service";
    public static final String SYSTEM_NETWORK_CAPABILITIES = "system_network_capabilities";
    public static final String SYSTEM_LINK_PROPERTIES = "system_link_properties";
    public static final String SYSTEM_DUMPSYS = "system_dumpsys";
    public static final String NATIVE_GETIFADDRS = "native_getifaddrs";
    public static final String NATIVE_IF_NAMETOINDEX = "native_if_nametoindex";
    public static final String NATIVE_IF_INDEXTONAME = "native_if_indextoname";
    public static final String NATIVE_PROCFS_FOPEN = "native_procfs_fopen";
    public static final String NATIVE_PROCFS_OPEN = "native_procfs_open";

    private XposedAttackVector() {}

    @StringRes
    public static int getShortLabelRes(@NonNull String vector) {
        switch (vector) {
            case NETWORK_CAPS_HAS_TRANSPORT_VPN:
                return R.string.xposed_attack_vector_short_network_caps_has_transport_vpn;
            case NETWORK_CAPS_TRANSPORT_INFO:
                return R.string.xposed_attack_vector_short_network_caps_transport_info;
            case LINK_PROPERTIES_INTERFACE_NAME:
                return R.string.xposed_attack_vector_short_link_properties_interface_name;
            case LINK_PROPERTIES_ALL_INTERFACES:
                return R.string.xposed_attack_vector_short_link_properties_all_interfaces;
            case LINK_PROPERTIES_ROUTES:
                return R.string.xposed_attack_vector_short_link_properties_routes;
            case LINK_PROPERTIES_DNS:
                return R.string.xposed_attack_vector_short_link_properties_dns;
            case PROCFS_JAVA:
                return R.string.xposed_attack_vector_short_procfs_java;
            case SYSTEM_PROXY_PROPERTIES:
                return R.string.xposed_attack_vector_short_system_proxy_properties;
            case NETWORK_INTERFACE_LIST:
                return R.string.xposed_attack_vector_short_network_interface_list;
            case NETWORK_INTERFACE_BY_NAME:
                return R.string.xposed_attack_vector_short_network_interface_by_name;
            case PACKAGE_MANAGER_INSTALLED_PACKAGES:
                return R.string.xposed_attack_vector_short_package_manager_installed_packages;
            case PACKAGE_MANAGER_INSTALLED_APPLICATIONS:
                return R.string.xposed_attack_vector_short_package_manager_installed_applications;
            case PACKAGE_MANAGER_PACKAGE_INFO:
                return R.string.xposed_attack_vector_short_package_manager_package_info;
            case PACKAGE_MANAGER_APPLICATION_INFO:
                return R.string.xposed_attack_vector_short_package_manager_application_info;
            case PACKAGE_MANAGER_QUERY_INTENT_SERVICES:
                return R.string.xposed_attack_vector_short_package_manager_query_intent_services;
            case PACKAGE_MANAGER_RESOLVE_SERVICE:
                return R.string.xposed_attack_vector_short_package_manager_resolve_service;
            case SYSTEM_NETWORK_CAPABILITIES:
                return R.string.xposed_attack_vector_short_system_network_capabilities;
            case SYSTEM_LINK_PROPERTIES:
                return R.string.xposed_attack_vector_short_system_link_properties;
            case SYSTEM_DUMPSYS:
                return R.string.xposed_attack_vector_short_system_dumpsys;
            case NATIVE_GETIFADDRS:
                return R.string.xposed_attack_vector_short_native_getifaddrs;
            case NATIVE_IF_NAMETOINDEX:
                return R.string.xposed_attack_vector_short_native_if_nametoindex;
            case NATIVE_IF_INDEXTONAME:
                return R.string.xposed_attack_vector_short_native_if_indextoname;
            case NATIVE_PROCFS_FOPEN:
                return R.string.xposed_attack_vector_short_native_procfs_fopen;
            case NATIVE_PROCFS_OPEN:
                return R.string.xposed_attack_vector_short_native_procfs_open;
            default:
                return R.string.xposed_attack_vector_short_unknown;
        }
    }

    @StringRes
    public static int getDetailLabelRes(@NonNull String vector) {
        switch (vector) {
            case NETWORK_CAPS_HAS_TRANSPORT_VPN:
                return R.string.xposed_attack_vector_detail_network_caps_has_transport_vpn;
            case NETWORK_CAPS_TRANSPORT_INFO:
                return R.string.xposed_attack_vector_detail_network_caps_transport_info;
            case LINK_PROPERTIES_INTERFACE_NAME:
                return R.string.xposed_attack_vector_detail_link_properties_interface_name;
            case LINK_PROPERTIES_ALL_INTERFACES:
                return R.string.xposed_attack_vector_detail_link_properties_all_interfaces;
            case LINK_PROPERTIES_ROUTES:
                return R.string.xposed_attack_vector_detail_link_properties_routes;
            case LINK_PROPERTIES_DNS:
                return R.string.xposed_attack_vector_detail_link_properties_dns;
            case PROCFS_JAVA:
                return R.string.xposed_attack_vector_detail_procfs_java;
            case SYSTEM_PROXY_PROPERTIES:
                return R.string.xposed_attack_vector_detail_system_proxy_properties;
            case NETWORK_INTERFACE_LIST:
                return R.string.xposed_attack_vector_detail_network_interface_list;
            case NETWORK_INTERFACE_BY_NAME:
                return R.string.xposed_attack_vector_detail_network_interface_by_name;
            case PACKAGE_MANAGER_INSTALLED_PACKAGES:
                return R.string.xposed_attack_vector_detail_package_manager_installed_packages;
            case PACKAGE_MANAGER_INSTALLED_APPLICATIONS:
                return R.string.xposed_attack_vector_detail_package_manager_installed_applications;
            case PACKAGE_MANAGER_PACKAGE_INFO:
                return R.string.xposed_attack_vector_detail_package_manager_package_info;
            case PACKAGE_MANAGER_APPLICATION_INFO:
                return R.string.xposed_attack_vector_detail_package_manager_application_info;
            case PACKAGE_MANAGER_QUERY_INTENT_SERVICES:
                return R.string.xposed_attack_vector_detail_package_manager_query_intent_services;
            case PACKAGE_MANAGER_RESOLVE_SERVICE:
                return R.string.xposed_attack_vector_detail_package_manager_resolve_service;
            case SYSTEM_NETWORK_CAPABILITIES:
                return R.string.xposed_attack_vector_detail_system_network_capabilities;
            case SYSTEM_LINK_PROPERTIES:
                return R.string.xposed_attack_vector_detail_system_link_properties;
            case SYSTEM_DUMPSYS:
                return R.string.xposed_attack_vector_detail_system_dumpsys;
            case NATIVE_GETIFADDRS:
                return R.string.xposed_attack_vector_detail_native_getifaddrs;
            case NATIVE_IF_NAMETOINDEX:
                return R.string.xposed_attack_vector_detail_native_if_nametoindex;
            case NATIVE_IF_INDEXTONAME:
                return R.string.xposed_attack_vector_detail_native_if_indextoname;
            case NATIVE_PROCFS_FOPEN:
                return R.string.xposed_attack_vector_detail_native_procfs_fopen;
            case NATIVE_PROCFS_OPEN:
                return R.string.xposed_attack_vector_detail_native_procfs_open;
            default:
                return R.string.xposed_attack_vector_detail_unknown;
        }
    }
}
