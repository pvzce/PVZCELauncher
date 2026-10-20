package com.tungsten.fcl.generic;

/** Redacts argument dumps and a known runtime secret, including split native stdout chunks. */
public final class LaunchSecretFilter {
    private final String secret;
    private String pending = "";

    public LaunchSecretFilter(String secret) {
        this.secret = secret == null ? "" : secret;
    }

    public String redact(String value) {
        return secret.isEmpty() ? value : value.replace(secret, "[redacted]");
    }

    public synchronized String accept(String chunk) {
        if (secret.isEmpty()) return chunk;
        String value = redact(pending + chunk);
        int keep = Math.min(secret.length() - 1, value.length());
        while (keep > 0 && !value.endsWith(secret.substring(0, keep))) keep--;
        pending = value.substring(value.length() - keep);
        return value.substring(0, value.length() - keep);
    }

    public synchronized String finish() {
        String result = pending.isEmpty() ? "" : "[redacted]";
        pending = "";
        return result;
    }

    public static String[] arguments(String[] source) {
        String[] result = source.clone();
        boolean next = false;
        for (int i = 0; i < result.length; i++) {
            String arg = source[i];
            if (next) {
                result[i] = "[redacted]";
                next = false;
                continue;
            }
            for (String flag : new String[] {"--accessToken", "--pvzceLoginCode"}) {
                if (arg.equals(flag)) next = true;
                else if (arg.startsWith(flag + "=")) result[i] = flag + "=[redacted]";
            }
        }
        return result;
    }
}
