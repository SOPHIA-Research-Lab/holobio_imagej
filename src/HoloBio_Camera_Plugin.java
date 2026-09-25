import ij.plugin.PlugIn;
import javax.swing.SwingUtilities;

/** Fiji plugin entry point — opens the RT DHM real-time reconstruction window. */
public class HoloBio_Camera_Plugin implements PlugIn {
    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(() -> new HoloBioRtDhmWindow().setVisible(true));
    }
}
