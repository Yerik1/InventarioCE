package com.example.myapplication;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.Intent;

import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.io.BufferedOutputStream;

public class DataBase {

    public interface Logger {
        void info(String msg);
        void error(String msg);
        void toast(String msg);
    }

    private final Context context;
    private final ActivityResultCaller caller;
    private final Logger logger;

    private final ActivityResultLauncher<String[]> openXlsxLauncher;
    private final ActivityResultLauncher<String> createXlsxLauncher;

    private List<String> headerNames = null;
    private int pickedIdCol = -1;
    private int pickedDescCol = -1;
    private Workbook sourceWb = null;

    // Abrir XLSX para borrar
    private final ActivityResultLauncher<String[]> openForDeleteLauncher;

    // Estado temporal para borrar
    private Workbook deleteWb = null;
    private Uri deleteUri = null;

    // Estructura mínima para mostrar opciones de borrado
    private static class RowInfo {
        int rowIndex;
        String id;
        String descripcion;
        RowInfo(int rowIndex, String id, String descripcion) {
            this.rowIndex = rowIndex;
            this.id = id;
            this.descripcion = descripcion;
        }
    }

    public DataBase(ActivityResultCaller caller, Context context, Logger logger) {
        this.caller = caller;
        this.context = context;
        this.logger = logger;

        openXlsxLauncher = caller.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onOpenXlsxResult
        );

        createXlsxLauncher = caller.registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                this::onCreateXlsxResult
        );

        openForDeleteLauncher = caller.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onOpenForDeleteResult
        );
    }

    public void startNuevoInventario() {
        logger.info("Selecciona el archivo XLSX de inventario...");
        openXlsxLauncher.launch(new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
        });
    }

    public void startBorrar() {
        logger.info("Selecciona el archivo XLSX para borrar un elemento...");
        openForDeleteLauncher.launch(new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
        });
    }

    private void onOpenXlsxResult(Uri uri) {
        if (uri == null) {
            logger.error("No se seleccionó archivo.");
            return;
        }
        try {
            ContentResolver cr = context.getContentResolver();
            try (InputStream is = cr.openInputStream(uri)) {
                sourceWb = WorkbookFactory.create(is);
            }

            Sheet sheet = sourceWb.getNumberOfSheets() > 0 ? sourceWb.getSheetAt(0) : null;
            if (sheet == null) {
                logger.error("El XLSX no tiene hojas.");
                closeSource();
                return;
            }

            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                logger.error("No se encontró fila de encabezados.");
                closeSource();
                return;
            }

            headerNames = new ArrayList<>();
            short min = header.getFirstCellNum();
            short max = header.getLastCellNum();
            for (int c = min; c < max; c++) {
                Cell cell = header.getCell(c);
                headerNames.add(cellToString(cell));
            }

            showPickColumnsDialog(headerNames);

        } catch (Exception e) {
            logger.error("Error leyendo XLSX: " + e.getMessage());
            closeSource();
        }
    }

    private void showPickColumnsDialog(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            logger.error("No hay encabezados para seleccionar columnas.");
            return;
        }

        final String[] items = headers.toArray(new String[0]);
        pickedIdCol = -1;
        pickedDescCol = -1;

        new AlertDialog.Builder(context)
                .setTitle("Selecciona columna de ID")
                .setSingleChoiceItems(items, -1, (dialog, which) -> pickedIdCol = which)
                .setPositiveButton("Siguiente", (d, w) -> {
                    if (pickedIdCol < 0) {
                        logger.toast("Debes seleccionar la columna de ID.");
                        return;
                    }
                    new AlertDialog.Builder(context)
                            .setTitle("Selecciona columna de Descripción")
                            .setSingleChoiceItems(items, -1, (dialog2, which2) -> pickedDescCol = which2)
                            .setPositiveButton("Continuar", (d2, w2) -> {
                                if (pickedDescCol < 0) {
                                    logger.toast("Debes seleccionar la columna de Descripción.");
                                    return;
                                }
                                if (pickedDescCol == pickedIdCol) {
                                    logger.toast("ID y Descripción no pueden ser la misma columna.");
                                    return;
                                }
                                exportFilteredXlsx();
                            })
                            .setNegativeButton("Cancelar", null)
                            .show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void exportFilteredXlsx() {
        if (sourceWb == null) {
            logger.error("No hay libro origen en memoria.");
            return;
        }
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String suggestedName = "inventario_" + time + ".xlsx";
        logger.info("Elige dónde guardar: " + suggestedName);
        createXlsxLauncher.launch(suggestedName);
    }

    private void onCreateXlsxResult(Uri destUri) {
        if (destUri == null) { logger.error("No se seleccionó destino para guardar."); return; }
        if (sourceWb == null) { logger.error("No hay libro origen cargado."); return; }

        // Ejecutar en segundo plano para evitar ANR/crash
        new Thread(() -> {
            int rowsExported = 0;
            try {
                // Construir el workbook de salida
                Workbook outWb = new XSSFWorkbook();
                Sheet outSheet = outWb.createSheet("Inventario");

                Row h = outSheet.createRow(0);
                h.createCell(0).setCellValue("id");
                h.createCell(1).setCellValue("descripcion");
                h.createCell(2).setCellValue("estado");

                Sheet inSheet = sourceWb.getSheetAt(0);
                int outRowIdx = 1;

                int first = inSheet.getFirstRowNum();
                int last  = inSheet.getLastRowNum();
                for (int r = first + 1; r <= last; r++) {
                    Row inRow = inSheet.getRow(r);
                    if (inRow == null) continue;

                    String id  = cellToString(inRow.getCell(pickedIdCol));
                    String des = cellToString(inRow.getCell(pickedDescCol));
                    if ((id == null || id.isEmpty()) && (des == null || des.isEmpty())) continue;

                    Row outRow = outSheet.createRow(outRowIdx++);
                    outRow.createCell(0).setCellValue(id == null ? "" : id);
                    outRow.createCell(1).setCellValue(des == null ? "" : des);
                    outRow.createCell(2).setCellValue("No encontrado");
                }
                rowsExported = outRowIdx - 1;

                outSheet.setColumnWidth(0, 20 * 256);
                outSheet.setColumnWidth(1, 40 * 256);
                outSheet.setColumnWidth(2, 18 * 256);

                // Escribir al SAF
                try (OutputStream os = context.getContentResolver().openOutputStream(destUri)) {
                    if (os == null) throw new IllegalStateException("OutputStream nulo para URI destino.");
                    outWb.write(os);
                    os.flush();
                }
                outWb.close();

                // ⚠️ No usar renameDocument: puede dejar 0B con algunos providers

                // Verificación post-escritura: consultar tamaño
                long size = -1L;
                try (android.database.Cursor c = context.getContentResolver()
                        .query(destUri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) size = c.getLong(0);
                }

                final int fRows = rowsExported;
                final long fSize = size;
                logger.info("Se exportaron " + fRows + " filas. Tamaño archivo: " + fSize + " bytes.");
                logger.toast("Inventario generado (" + fRows + " filas).");

            } catch (SecurityException se) {
                logger.error("Permisos para escribir el archivo no concedidos: " + se.getMessage());
            } catch (Exception e) {
                logger.error("Error al guardar XLSX: " + e.getMessage());
            } finally {
                closeSource();
                headerNames = null;
                pickedIdCol = -1;
                pickedDescCol = -1;
            }
        }).start();
    }

    private void closeSource() {
        try { if (sourceWb != null) sourceWb.close(); } catch (Exception ignore) {}
        sourceWb = null;
    }

    private static String cellToString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    double v = cell.getNumericCellValue();
                    long lv = (long) v;
                    return (Math.abs(v - lv) < 1e-9) ? Long.toString(lv) : Double.toString(v);
                }
            case BOOLEAN: return Boolean.toString(cell.getBooleanCellValue());
            case FORMULA:
                try { return cell.getStringCellValue(); }
                catch (Exception e) { return Double.toString(cell.getNumericCellValue()); }
            default:      return "";
        }
    }

    private void onOpenForDeleteResult(Uri uri) {
        if (uri == null) {
            logger.error("No se seleccionó archivo para borrar.");
            return;
        }
        deleteUri = uri;

        try {
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                deleteWb = WorkbookFactory.create(is);
            }

            Sheet sheet = deleteWb.getNumberOfSheets() > 0 ? deleteWb.getSheetAt(0) : null;
            if (sheet == null) {
                logger.error("El XLSX no tiene hojas.");
                safeCloseDeleteWb();
                return;
            }

            // Encontrar índices de columnas por encabezado (id, descripcion)
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                logger.error("No se encontró fila de encabezados.");
                safeCloseDeleteWb();
                return;
            }

            int colId = -1, colDesc = -1;
            short min = header.getFirstCellNum();
            short max = header.getLastCellNum();
            for (int c = min; c < max; c++) {
                String name = cellToString(header.getCell(c)).trim().toLowerCase();
                if (name.equals("id")) colId = c;
                else if (name.equals("descripcion")) colDesc = c;
            }
            if (colId < 0 || colDesc < 0) {
                logger.error("No se encontraron columnas 'id' y 'descripcion' en el encabezado.");
                safeCloseDeleteWb();
                return;
            }

            // Construir lista de filas (sin encabezado)
            List<RowInfo> items = new ArrayList<>();
            int first = sheet.getFirstRowNum();
            int last  = sheet.getLastRowNum();
            for (int r = first + 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String id  = cellToString(row.getCell(colId));
                String ds  = cellToString(row.getCell(colDesc));
                if ((id == null || id.isEmpty()) && (ds == null || ds.isEmpty())) continue;
                items.add(new RowInfo(r, id == null ? "" : id, ds == null ? "" : ds));
            }

            if (items.isEmpty()) {
                logger.toast("No hay registros para borrar.");
                safeCloseDeleteWb();
                return;
            }

            showDeletePickerDialog(items, colId, colDesc);

        } catch (Exception e) {
            logger.error("Error abriendo XLSX para borrar: " + e.getMessage());
            safeCloseDeleteWb();
        }
    }

    private void showDeletePickerDialog(List<RowInfo> items, int colId, int colDesc) {
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).id + " – " + items.get(i).descripcion;
        }

        final int[] pickedIndex = {-1};

        new AlertDialog.Builder(context)
                .setTitle("Selecciona el elemento a borrar")
                .setSingleChoiceItems(labels, -1, (d, which) -> pickedIndex[0] = which)
                .setPositiveButton("Continuar", (d, w) -> {
                    if (pickedIndex[0] < 0) {
                        logger.toast("Debes seleccionar un elemento.");
                        return;
                    }
                    RowInfo chosen = items.get(pickedIndex[0]);
                    confirmAndDelete(chosen, colId, colDesc);
                })
                .setNegativeButton("Cancelar", (d, w) -> {
                    // cancelar y limpiar
                    safeCloseDeleteWb();
                })
                .show();
    }

    private void confirmAndDelete(RowInfo chosen, int colId, int colDesc) {
        String msg = "¿Eliminar \"" + chosen.id + ": " + chosen.descripcion + "\"?";
        new AlertDialog.Builder(context)
                .setTitle("Confirmar borrado")
                .setMessage(msg)
                .setPositiveButton("Eliminar", (d, w) -> performDelete(chosen))
                .setNegativeButton("Cancelar", (d, w) -> safeCloseDeleteWb())
                .show();
    }

    private void performDelete(RowInfo chosen) {
        if (deleteWb == null || deleteUri == null) {
            logger.error("No hay archivo cargado para borrar.");
            safeCloseDeleteWb();
            return;
        }

        new Thread(() -> {
            try {
                Sheet sheet = deleteWb.getSheetAt(0);

                int lastRow = sheet.getLastRowNum();
                int rowIdx  = chosen.rowIndex;

                // Borrar la fila: mover todas las siguientes una hacia arriba
                if (rowIdx >= 0 && rowIdx < lastRow) {
                    sheet.shiftRows(rowIdx + 1, lastRow, -1);
                }
                Row last = sheet.getRow(lastRow);
                if (last != null) {
                    sheet.removeRow(last);
                }

                // Guardar sobre el mismo URI
                try (OutputStream os = context.getContentResolver().openOutputStream(deleteUri)) {
                    if (os == null) throw new IllegalStateException("OutputStream nulo para URI destino.");
                    deleteWb.write(os);
                    os.flush();
                }
                deleteWb.close();

                // Verificación opcional: tamaño
                long size = -1L;
                try (android.database.Cursor c = context.getContentResolver()
                        .query(deleteUri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) size = c.getLong(0);
                }

                logger.toast("Elemento eliminado: " + chosen.id);
                logger.info("Archivo actualizado. Tamaño: " + size + " bytes.");

            } catch (Exception e) {
                logger.error("Error al borrar/guardar: " + e.getMessage());
            } finally {
                safeCloseDeleteWb();
            }
        }).start();
    }

    private void safeCloseDeleteWb() {
        try { if (deleteWb != null) deleteWb.close(); } catch (Exception ignore) {}
        deleteWb = null;
        deleteUri = null;
    }
}
