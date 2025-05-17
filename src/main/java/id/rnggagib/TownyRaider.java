package id.rnggagib;

import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/*
 * townyraider java plugin
 */
public class Plugin extends JavaPlugin
{
  private static final Logger LOGGER=Logger.getLogger("townyraider");

  public void onEnable()
  {
    LOGGER.info("townyraider enabled");
  }

  public void onDisable()
  {
    LOGGER.info("townyraider disabled");
  }
}
