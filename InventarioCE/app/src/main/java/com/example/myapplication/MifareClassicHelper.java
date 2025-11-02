package com.example.myapplication;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class MifareClassicHelper {

    // --------------------------------------------------------------------------------------------
    // Atributos (Campos/Propiedades)
    // --------------------------------------------------------------------------------------------

    /** Descripción: Clave por defecto (0xFF...) usada para autenticar sectores A/B en MIFARE Classic. */
    static final byte[] KEY_DEFAULT = new byte[]{
            (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF
    };

    // --------------------------------------------------------------------------------------------
    // Funciones públicas
    // --------------------------------------------------------------------------------------------

    /**
     * Entradas:
     *   - tag (Tag): instancia del tag NFC detectado.
     * Salidas:
     *   - (String): nombres de tecnologías del tag en formato legible (p.ej. "Techs: NfcA, MifareClassic").
     * Descripción:
     *   Construye un string con la lista de tecnologías disponibles en el Tag, útil para depurar
     *   y mostrar información en UI.
     */
    static String techsString(Tag tag) {
        // [Sección] Nulos y preparación
        if (tag == null) return "(sin tag)";

        // [Sección] Recorrer la lista de tecnologías y limpiar el nombre (quitar paquete)
        String[] techs = tag.getTechList();
        StringBuilder sb = new StringBuilder("Techs: ");
        for (int i = 0; i < techs.length; i++) {
            String t = techs[i];
            int lastDot = t.lastIndexOf('.');
            sb.append(lastDot >= 0 ? t.substring(lastDot + 1) : t);
            if (i < techs.length - 1) sb.append(", ");
        }
        return sb.toString();
    }

    /**
     * Entradas:
     *   - tag (Tag): tag NFC detectado.
     *   - sectorIndex (int): índice del sector a leer (0..n).
     *   - key (byte[]): clave para autenticar (Key A o B; se intenta A y luego B).
     * Salidas:
     *   - (String): el PRIMER grupo de dígitos contiguos de longitud ≥ 3 hallado en el contenido ASCII
     *               de los bloques de datos del sector (excluye trailer). Devuelve null si no hay match
     *               o si falla la autenticación/lectura.
     * Descripción:
     *   Autentica el sector, lee los bloques de datos (excluyendo el trailer), convierte a ASCII
     *   "imprimible" y retorna el primer número con 3 o más dígitos (útil para extraer IDs como 97175).
     */
    static String readOnlyNumberFromSector(Tag tag, int sectorIndex, byte[] key) {
        // [Sección] Obtener interfaz MIFARE y validar
        MifareClassic mc = MifareClassic.get(tag);
        if (mc == null) return null;

        try {
            // [Sección] Conexión + autenticación con Key A y fallback a Key B
            mc.connect();
            boolean auth = mc.authenticateSectorWithKeyA(sectorIndex, key);
            if (!auth) auth = mc.authenticateSectorWithKeyB(sectorIndex, key);
            if (!auth) return null;

            // [Sección] Calcular rango de bloques de datos del sector (excluye trailer)
            int firstBlock = mc.sectorToBlock(sectorIndex);
            int dataBlocks = mc.getBlockCountInSector(sectorIndex) - 1; // último es trailer
            int totalBytes = dataBlocks * MifareClassic.BLOCK_SIZE;

            // [Sección] Leer todos los bloques de datos del sector de forma contigua
            byte[] all = new byte[totalBytes];
            int p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, all, p, b.length);
                p += b.length;
            }

            // [Sección] Convertir a ASCII imprimible y buscar el primer grupo de dígitos (≥3)
            String printable = toAsciiPrintable(all);
            Matcher m = Pattern.compile("\\d{3,}").matcher(printable);
            return m.find() ? m.group() : null;

        } catch (Exception e) {
            android.util.Log.w("MIFARE_READ_NUM", e);
            return null;
        } finally {
            // [Sección] Cierre defensivo
            try { mc.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Entradas:
     *   - tag (Tag): tag NFC detectado.
     *   - sectorIndex (int): índice de sector a inspeccionar.
     *   - regex (String): expresión regular a buscar (p.ej. "\\d{3,}" para números de ≥3 dígitos).
     *   - key (byte[]): clave de autenticación (se intenta A y luego B).
     * Salidas:
     *   - (FieldDetect): metadatos del match (valor, sector, byteStart, byteLength) respecto a la zona
     *                    de datos contiguos del sector. Devuelve null si no hay match o falla autenticación.
     * Descripción:
     *   Autentica el sector y busca un patrón regex en la concatenación ASCII de sus bloques de datos
     *   (excluye trailer). Devuelve la localización del match para luego poder reemplazarlo.
     */
    static FieldDetect detectFieldInSector(Tag tag, int sectorIndex, String regex, byte[] key) {
        // [Sección] Obtener interfaz MIFARE y validar
        MifareClassic mc = MifareClassic.get(tag);
        if (mc == null) return null;
        try {
            // [Sección] Conexión + autenticación con Key A/B
            mc.connect();
            boolean auth = mc.authenticateSectorWithKeyA(sectorIndex, key);
            if (!auth) auth = mc.authenticateSectorWithKeyB(sectorIndex, key);
            if (!auth) return null;

            // [Sección] Determinar bloques de datos y buffer contiguo
            int firstBlock = mc.sectorToBlock(sectorIndex);
            int dataBlocks = mc.getBlockCountInSector(sectorIndex) - 1; // exclude trailer
            int totalBytes = dataBlocks * MifareClassic.BLOCK_SIZE;
            byte[] all = new byte[totalBytes];

            // [Sección] Leer bloques de datos contiguos
            int p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, all, p, b.length);
                p += b.length;
            }

            // [Sección] Buscar regex en la representación ASCII imprimible
            String printable = toAsciiPrintable(all);
            Pattern pat = Pattern.compile(regex);
            Matcher m = pat.matcher(printable);
            if (m.find()) {
                int startPrintable = m.start();
                int length = m.end() - m.start();
                return new FieldDetect(m.group(), sectorIndex, startPrintable, length);
            } else {
                return null;
            }
        } catch (Exception e) {
            android.util.Log.w("MIFARE_DETECT", e);
            return null;
        } finally {
            // [Sección] Cierre defensivo
            try { mc.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Entradas:
     *   - tag (Tag): tag NFC detectado (mismo que detectó el campo).
     *   - field (FieldDetect): localización del campo a reemplazar (valor, sector, byteStart...).
     *   - newValue (String): nuevo contenido ASCII a escribir (se truncará si no cabe).
     *   - key (byte[]): clave de autenticación (se intenta A y luego B).
     * Salidas:
     *   - (WriteOutcome): resultado de la operación con mensaje para el usuario y detalle técnico.
     * Descripción:
     *   Reemplaza in-place el campo detectado por el nuevo valor, expandiendo hasta donde lo permita
     *   el espacio contiguo compuesto por dígitos y/o padding (espacio 0x20 o 0x00). Escribe todos
     *   los bloques del sector, relee y verifica byte a byte la zona afectada.
     */
    static WriteOutcome replaceFieldInSector(Tag tag, FieldDetect field, String newValue, byte[] key) {
        // [Sección] Validaciones de entrada
        if (tag == null || field == null) return WriteOutcome.fail("Parámetros inválidos.", "null input");

        // [Sección] Obtener interfaz MIFARE
        MifareClassic mc = MifareClassic.get(tag);
        if (mc == null) return WriteOutcome.fail("Tag no soporta MifareClassic.", "mc==null");

        // [Sección] Convertir nuevo valor a bytes UTF-8
        byte[] newBytes = (newValue == null) ? new byte[0] : newValue.getBytes(StandardCharsets.UTF_8);

        try {
            // [Sección] Conectar y autenticar sector
            mc.connect();
            boolean auth = mc.authenticateSectorWithKeyA(field.sectorIndex, key);
            if (!auth) auth = mc.authenticateSectorWithKeyB(field.sectorIndex, key);
            if (!auth) return WriteOutcome.fail("No se pudo autenticar sector para escribir.", "auth failed sector=" + field.sectorIndex);

            // [Sección] Calcular bloques de datos y leer todo el sector (excepto trailer)
            int firstBlock = mc.sectorToBlock(field.sectorIndex);
            int dataBlocks = mc.getBlockCountInSector(field.sectorIndex) - 1; // excluye trailer
            int totalBytes = dataBlocks * MifareClassic.BLOCK_SIZE;

            byte[] all = new byte[totalBytes];
            int p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, all, p, b.length);
                p += b.length;
            }

            // [Sección] Determinar cuánto espacio contiguo hay desde byteStart
            int start = field.byteStart;
            if (start < 0 || start >= all.length) {
                return WriteOutcome.fail("Posición de inicio fuera de rango.", "start=" + start + " total=" + all.length);
            }
            int maxSpan = computeNumericFieldSpan(all, start);
            if (maxSpan <= 0) {
                return WriteOutcome.fail("No hay espacio contiguo disponible para expandir el campo.", "maxSpan<=0");
            }

            // [Sección] Preparar reemplazo: truncar si excede el span y rellenar con espacios
            int cap = Math.min(maxSpan, all.length - start);
            byte[] replacement = new byte[cap];
            java.util.Arrays.fill(replacement, (byte)0x20); // padding con espacios
            int copyLen = Math.min(cap, newBytes.length);
            System.arraycopy(newBytes, 0, replacement, 0, copyLen);

            // [Sección] Sobrescribir en el buffer contiguo
            System.arraycopy(replacement, 0, all, start, cap);

            // [Sección] Escribir bloque a bloque todo el segmento de datos del sector
            for (int i = 0; i < dataBlocks; i++) {
                int blockIndex = firstBlock + i;
                byte[] blockData = new byte[MifareClassic.BLOCK_SIZE];
                System.arraycopy(all, i * MifareClassic.BLOCK_SIZE, blockData, 0, MifareClassic.BLOCK_SIZE);
                mc.writeBlock(blockIndex, blockData);
            }

            // [Sección] Releer para verificar byte a byte la zona reemplazada
            byte[] back = new byte[totalBytes];
            p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, back, p, b.length);
                p += b.length;
            }
            for (int i = 0; i < cap; i++) {
                if (back[start + i] != replacement[i]) {
                    return WriteOutcome.fail("Verificación falló (bytes distintos tras escribir).", "verify mismatch at +" + i);
                }
            }

            // [Sección] Mensaje final con aviso de truncamiento si aplica
            String note = (newBytes.length > cap)
                    ? " (truncado a " + cap + " bytes disponibles)"
                    : "";
            return WriteOutcome.ok("Campo actualizado a '" + newValue + "'" + note);

        } catch (java.io.IOException e) {
            return WriteOutcome.fail("Error E/S durante la escritura. Mantén el tag quieto.", "IOException: " + e.getMessage());
        } catch (Exception e) {
            return WriteOutcome.fail("Fallo inesperado al escribir.", "Exception: " + e.getMessage());
        } finally {
            // [Sección] Cierre defensivo
            try { mc.close(); } catch (Exception ignored) {}
        }
    }

    // --------------------------------------------------------------------------------------------
    // Funciones privadas (helpers)
    // --------------------------------------------------------------------------------------------

    /**
     * Entradas:
     *   - all (byte[]): buffer contiguo de los bytes de datos del sector.
     *   - start (int): posición de inicio para medir el span.
     * Salidas:
     *   - (int): cantidad de bytes contiguos desde start que son dígitos '0'..'9' o padding (0x20 o 0x00).
     * Descripción:
     *   Recorre el buffer a partir de start contando los bytes válidos para un campo numérico
     *   expandible (dígitos o relleno). Se usa para saber cuánto cabe al reemplazar.
     */
    private static int computeNumericFieldSpan(byte[] all, int start) {
        // [Sección] Recorrer mientras se mantenga dígito o padding
        int i = start;
        while (i < all.length) {
            int v = all[i] & 0xFF;
            boolean isDigit = (v >= 0x30 && v <= 0x39);
            boolean isPad   = (v == 0x20 || v == 0x00);
            if (isDigit || isPad) i++; else break;
        }
        return i - start;
    }

    /**
     * Entradas:
     *   - b (byte[]): arreglo de bytes leídos.
     * Salidas:
     *   - (String): texto con caracteres ASCII imprimibles; bytes no imprimibles se muestran como '.'.
     * Descripción:
     *   Convierte bytes a una representación segura para UI/logs y recorta puntos finales (padding).
     */
    static String toAsciiPrintable(byte[] b) {
        // [Sección] Nulos
        if (b == null) return "";

        // [Sección] Convertir byte por byte a rango ASCII imprimible
        StringBuilder sb = new StringBuilder(b.length);
        for (byte x : b) {
            int v = x & 0xFF;
            if (v >= 32 && v <= 126) sb.append((char) v);
            else sb.append('.');
        }

        // [Sección] Recortar padding de puntos al final
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '.') end--;
        return sb.substring(0, end);
    }
}
