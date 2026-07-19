package com.istech.buscourse.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `map_data_package`（オフライン地図パッケージのメタデータ。設計書§3.5・§5.6.4）。
 *
 * `.iscmap`インポート時（設計書§5.6.3手順6）に `manifest.json` から1行としてUPSERTされる。
 * 地図タイル・スタイル・グリフの参照情報のみを保持し、コース・停留所・区間軌跡の実データは
 * 含まない（それらは同インポートの手順7で既存の `course` / `bus_stop_card` / `segment_track`
 * パイプラインへ合流する。設計書§3.5 `map_data_package` の統合注記）。
 *
 * 列構成は、実運用で検証済みの `.iscmap`
 * （例：`D:/ishix/BusCourse/maps/saitama-east-2026.iscmap`）から実測した `manifest.json` の
 * 実物スキーマに合わせている。設計書§5.6.2記載のサンプルJSONは `attribution` / `glyphs` キーを
 * 含まないが、これは設計書執筆後に生まれた正当な拡張（オフライン端末はグリフをネットワーク取得
 * できないため `.iscmap` 内に同梱する方式。PC側ツール `mapkit_pack.py` 内に自己文書化済み）であり、
 * 実物優先でこちらを採用する。
 *
 * `preparedAt` は設計書§3.5のCREATE TABLE草案では `INTEGER`（epoch想定）だが、実際の
 * `manifest.json` はISO8601文字列（例："2026-07-11T14:29:13+09:00"）で提供されるため、
 * 変換によるロス・タイムゾーン情報の欠落を避けてそのまま `TEXT` で保持する
 * （`importedAt` はアプリ側で採番するため、既存の `created_at` / `updated_at` 慣習どおり
 * epoch millisの `Long` のまま）。
 */
@Entity(tableName = "map_data_package")
data class MapDataPackageEntity(
    @PrimaryKey @ColumnInfo(name = "region_id") val regionId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    /** manifest.json の `preparedAt`（ISO8601文字列）をそのまま保持する。上記クラスKDoc参照。 */
    @ColumnInfo(name = "prepared_at") val preparedAt: String,
    @ColumnInfo(name = "prepared_by") val preparedBy: String,
    /** 地図データの帰属表示（manifest.json `attribution`、設計書執筆後の拡張キー。上記クラスKDoc参照）。 */
    val attribution: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "mbtiles_rel_path") val mbtilesRelPath: String,
    @ColumnInfo(name = "mbtiles_sha256") val mbtilesSha256: String,
    val minzoom: Int,
    val maxzoom: Int,
    @ColumnInfo(name = "bounds_west") val boundsWest: Double,
    @ColumnInfo(name = "bounds_south") val boundsSouth: Double,
    @ColumnInfo(name = "bounds_east") val boundsEast: Double,
    @ColumnInfo(name = "bounds_north") val boundsNorth: Double,
    @ColumnInfo(name = "style_rel_path") val styleRelPath: String,
    @ColumnInfo(name = "style_sha256") val styleSha256: String,
    /** グリフPBF一式のディレクトリ相対パス（manifest.json `glyphs.dir`、通常 "glyphs/"）。 */
    @ColumnInfo(name = "glyphs_dir_rel_path") val glyphsDirRelPath: String,
    /**
     * manifest.json `glyphs.fontstacks`（文字列配列）をカンマ区切りで保持する。
     * 本DBにTypeConverterは未導入のため、新規導入は避けシンプルな文字列で持つ。
     */
    @ColumnInfo(name = "glyph_fontstacks_csv") val glyphFontstacksCsv: String,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    /**
     * 現在地図表示に使用中のパッケージ（設計書§5.6.4）。`bus_stop_card.is_archived` と同様、
     * 高々1行のみtrueとなる単一選択パターン（[MapDataPackageDao.setSelected] が担保）。
     */
    @ColumnInfo(name = "is_selected", defaultValue = "0") val isSelected: Boolean = false,
)
