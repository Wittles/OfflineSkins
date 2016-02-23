package lain.mods.skins.providers;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import lain.mods.skins.LegacyConversion;
import lain.mods.skins.PlayerUtils;
import lain.mods.skins.SkinData;
import lain.mods.skins.api.ISkin;
import lain.mods.skins.api.ISkinProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ObjectUtils;
import com.google.common.base.Strings;

public class CrafatarCachedSkinProvider implements ISkinProvider
{

    private static final ExecutorService pool = Executors.newCachedThreadPool();
    private static final BufferedImage dummy = new BufferedImage(1, 1, 2);

    private File _workDir;

    public CrafatarCachedSkinProvider()
    {
        File file1 = new File(Minecraft.getMinecraft().mcDataDir, "cachedImages");
        if (!file1.exists())
            file1.mkdirs();
        File file2 = new File(file1, "crafatar");
        if (!file2.exists())
            file2.mkdirs();
        prepareWorkDir(_workDir = new File(file2, "skins"));
    }

    @Override
    public ISkin getSkin(AbstractClientPlayer player)
    {
        final SkinData data = new SkinData();
        data.profile = player.getGameProfile();
        final boolean skipUUID = PlayerUtils.isOfflinePlayer(player);
        final UUID fbID = player.getUniqueID();
        pool.execute(new Runnable()
        {

            @Override
            public void run()
            {
                BufferedImage image = null;
                UUID uuid = ObjectUtils.defaultIfNull(data.profile.getId(), fbID);
                String name = ObjectUtils.defaultIfNull(data.profile.getName(), "");

                if (!skipUUID)
                {
                    for (int n = 0; n < 5; n++)
                        try
                        {
                            if ((image = readImageCached(_workDir, uuid.toString(), new URL(String.format("https://crafatar.com/skins/%s", uuid)), Minecraft.getMinecraft().getProxy())) != null)
                                break;
                        }
                        catch (IOException e)
                        {
                        }
                }
                if (image == null)
                {
                    for (int n = 0; n < 5; n++)
                        try
                        {
                            if ((image = readImageCached(_workDir, name, new URL(String.format("https://crafatar.com/skins/%s", name)), Minecraft.getMinecraft().getProxy())) != null)
                                break;
                        }
                        catch (IOException e)
                        {
                        }
                }

                if (image != null && image != dummy)
                {
                    String type = SkinData.judgeSkinType(image);
                    if ("legacy".equals(type))
                        type = "default";
                    image = new LegacyConversion().convert(image);
                    data.put(image, type);
                }
            }

        });
        return data;
    }

    private void prepareWorkDir(File workDir)
    {
        if (!workDir.exists())
        {
            workDir.mkdirs();
        }
        else
        {
            for (File f : workDir.listFiles(new FileFilter()
            {

                @Override
                public boolean accept(File pathname)
                {
                    if (pathname.getName().toLowerCase().endsWith(".validtime"))
                        return true;
                    return false;
                }

            }))
            {
                String validtime = null;
                try
                {
                    validtime = Strings.emptyToNull(FileUtils.readFileToString(f, "UTF-8"));
                }
                catch (IOException e)
                {
                    validtime = null;
                }
                long t = -1;
                try
                {
                    if (validtime != null)
                        t = Long.parseLong(validtime);
                    else
                        t = -1;
                }
                catch (NumberFormatException e)
                {
                    t = -1;
                }
                if (System.currentTimeMillis() >= t)
                {
                    String name = f.getName().substring(0, f.getName().length() - 10);
                    File f1 = new File(f.getParentFile(), name);
                    File f2 = new File(f.getParentFile(), name + ".etag");
                    File f3 = f;
                    if (f1.exists())
                        f1.delete();
                    if (f2.exists())
                        f2.delete();
                    if (f3.exists())
                        f3.delete();
                }
            }
        }
    }

    private BufferedImage readImageCached(File workDir, String local, URL remote, Proxy proxy) throws IOException
    {
        File file1 = new File(workDir, local);
        File file2 = new File(workDir, local + ".etag");
        File file3 = new File(workDir, local + ".validtime");

        String etag = null;
        if (file1.exists() && file2.exists())
        {
            try
            {
                etag = Strings.emptyToNull(FileUtils.readFileToString(file2, "UTF-8"));
            }
            catch (IOException e)
            {
                etag = null;
            }
        }

        String validtime = null;
        try
        {
            validtime = Strings.emptyToNull(FileUtils.readFileToString(file3, "UTF-8"));
        }
        catch (IOException e)
        {
            validtime = null;
        }
        long t = -1;
        try
        {
            if (validtime != null)
                t = Long.parseLong(validtime);
            else
                t = -1;
        }
        catch (NumberFormatException e)
        {
            t = -1;
        }

        HttpURLConnection conn = (HttpURLConnection) remote.openConnection(proxy);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(10000);
        if (etag != null && System.currentTimeMillis() < t)
            conn.setRequestProperty("If-None-Match", etag);
        conn.connect();
        int code = conn.getResponseCode();
        if (code / 100 == 2)
        {
            etag = Strings.emptyToNull(conn.getHeaderField("Etag"));
            t = Math.max(conn.getExpiration(), System.currentTimeMillis() + 60000);
            try
            {
                FileUtils.copyInputStreamToFile(conn.getInputStream(), file1);
                if (etag != null)
                    FileUtils.writeStringToFile(file2, etag, "UTF-8");
                FileUtils.writeStringToFile(file3, Long.toString(t), "UTF-8");
            }
            catch (IOException e)
            {
                if (file1.exists())
                    file1.delete();
                if (file2.exists())
                    file2.delete();
                if (file3.exists())
                    file3.delete();
            }
        }
        else if (code == 404)
        {
            return dummy;
        }

        if (!file1.exists())
            return null;
        return ImageIO.read(file1);
    }

}
