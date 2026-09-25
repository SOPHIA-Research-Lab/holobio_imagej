import ij.plugin.PlugIn;
import javax.swing.SwingUtilities;

/** Fiji plugin entry point — opens the RT DLHM real-time reconstruction window. */
public class HoloBio_RtDlhm_Plugin implements PlugIn {
    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(() -> new HoloBioRtDlhmWindow().setVisible(true));
    }
}
