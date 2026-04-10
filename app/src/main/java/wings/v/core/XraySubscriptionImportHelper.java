package wings.v.core;

import android.content.Context;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.SignatureDeclareThrowsException",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LawOfDemeter",
    }
)
public final class XraySubscriptionImportHelper {

    private XraySubscriptionImportHelper() {}

    public static ImportResult importAndRefresh(Context context, WingsImportParser.ImportedConfig importedConfig)
        throws Exception {
        Context appContext = context.getApplicationContext();
        AppPrefs.applyImportedConfig(appContext, importedConfig);
        XraySubscriptionUpdater.RefreshResult refreshResult = XraySubscriptionUpdater.refreshAll(
            appContext,
            null,
            true
        );
        boolean hasProfiles = !XrayStore.getProfiles(appContext).isEmpty();
        if (hasProfiles) {
            XrayStore.setBackendType(appContext, BackendType.XRAY);
        }
        return new ImportResult(refreshResult, hasProfiles);
    }

    public static final class ImportResult {

        public final XraySubscriptionUpdater.RefreshResult refreshResult;
        public final boolean hasProfiles;

        ImportResult(XraySubscriptionUpdater.RefreshResult refreshResult, boolean hasProfiles) {
            this.refreshResult = refreshResult;
            this.hasProfiles = hasProfiles;
        }
    }
}
