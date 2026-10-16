package cn.personal.worktimesync;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class CredentialStore {
    private static final String ALIAS="work-time-feishu";
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)) {
            KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    static void save(Context context,String appId,String secret) throws Exception {
        JSONObject data=new JSONObject();data.put("app_id",appId);data.put("app_secret",secret);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(data.toString().getBytes("UTF-8"));
        if(!context.getSharedPreferences("credentials",0).edit()
            .putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .putString("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit()) throw new Exception("授权配置保存失败");
    }
    static JSONObject read(Context context) throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("credentials",0);
        if(!prefs.contains("data"))throw new Exception("请先配置专用飞书应用");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(prefs.getString("data",""),Base64.NO_WRAP)),"UTF-8"));
    }
}
