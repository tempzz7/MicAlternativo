package br.com.micalternativo;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/**
 * Bloco de configurações rápidas "Gravar áudio": um toque abre o app
 * já gravando com o microfone salvo como padrão.
 */
public class RecordTileService extends TileService {
    @Override
    public void onClick() {
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(MainActivity.ACTION_RECORD_NOW);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(i);
        }
    }
}
