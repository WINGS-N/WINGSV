package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import wings.v.core.UiPrefs;
import wings.v.databinding.ActivityUiReorderListBinding;
import wings.v.ui.UiReorderListController;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor", "PMD.LawOfDemeter", "PMD.OnlyOneReturn" })
public class NotificationSettingsActivity extends AppCompatActivity {

    private ActivityUiReorderListBinding binding;

    public static Intent createIntent(Context context) {
        return new Intent(context, NotificationSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUiReorderListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setTitle(getString(R.string.ui_settings_notification_title));
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);

        binding.textHeaderTitle.setText(R.string.ui_settings_notification_title);
        binding.textHeaderSummary.setText(R.string.ui_settings_drag_hint);

        Map<String, Integer> labels = notificationLabels();
        List<String> savedOrder = UiPrefs.getNotificationOrder(this);
        List<UiReorderListController.Entry> entries = new ArrayList<>(savedOrder.size());
        for (String key : savedOrder) {
            Integer titleRes = labels.get(key);
            if (titleRes == null) {
                continue;
            }
            entries.add(new UiReorderListController.Entry(key, getString(titleRes), false));
        }

        UiReorderListController controller = new UiReorderListController(
            this,
            entries,
            UiPrefs.getNotificationHidden(this),
            (order, hidden) -> {
                UiPrefs.setNotificationOrder(this, order);
                UiPrefs.setNotificationHidden(this, hidden);
            }
        );
        controller.attach(binding.recyclerItems);
    }

    private Map<String, Integer> notificationLabels() {
        LinkedHashMap<String, Integer> labels = new LinkedHashMap<>();
        labels.put(UiPrefs.NOTIF_STATUS, R.string.notification_item_status);
        labels.put(UiPrefs.NOTIF_SPEED, R.string.notification_item_speed);
        labels.put(UiPrefs.NOTIF_TRAFFIC_TOTAL, R.string.notification_item_traffic_total);
        labels.put(UiPrefs.NOTIF_TRAFFIC_TX, R.string.notification_item_traffic_tx);
        labels.put(UiPrefs.NOTIF_TRAFFIC_RX, R.string.notification_item_traffic_rx);
        labels.put(UiPrefs.NOTIF_VK_TURN_STREAMS, R.string.notification_item_vk_turn_streams);
        labels.put(UiPrefs.NOTIF_DTLS_HEARTBEAT, R.string.notification_item_dtls_heartbeat);
        return labels;
    }
}
