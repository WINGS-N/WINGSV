package wings.v.core;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

public final class ByeDpiDomainList {

    public final String id;
    public final String name;
    public final ArrayList<String> domains;
    public final boolean isActive;
    public final boolean isBuiltIn;

    public ByeDpiDomainList(
        @NonNull String id,
        @NonNull String name,
        @NonNull List<String> domains,
        boolean isActive,
        boolean isBuiltIn
    ) {
        this.id = id;
        this.name = name;
        this.domains = new ArrayList<>(domains);
        this.isActive = isActive;
        this.isBuiltIn = isBuiltIn;
    }
}
