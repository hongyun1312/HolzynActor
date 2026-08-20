/**
 * HarmonyOS 图标注册表（华为鸿蒙 HarmonyOS_Icons.zip，2026-08-18 全量接入）。
 * <p>职责：① 用 `?raw` 静态引入已提取到 src/assets/icons 的 HarmonyOS SVG 文本；
 * ② 提供「Element Plus 图标名 → HarmonyOS 图标文件名」的映射（HIcon 组件按此解析）。</p>
 * <p>说明：图标 SVG 已在提取时统一把 `fill="#000000"` 改为 `fill="currentColor"`，
 * 由 HIcon 组件渲染时继承当前文字颜色，随深浅主题/主色自动适配。</p>
 * <p>无直接对应图标时的近似替换（华为鸿蒙图标集无太阳/月亮/地球/书本/仪表/钥匙/魔法棒/图表等）：
 * 太阳→ic_public_brightness、月亮→ic_public_brightness_filled、魔法棒→ic_public_highlight、
 * 仪表盘→ic_public_home、知识库→ic_public_albums、世界观→ic_public_worldclock、
 * API→ic_public_security、用量→ic_public_view_list、展开箭头→ic_public_drawer。</p>
 * <p>所属模块：shared/components（通用组件）</p>
 */
import ic_public_add from '@/assets/icons/ic_public_add.svg?raw'
import ic_public_albums from '@/assets/icons/ic_public_albums.svg?raw'
import ic_public_arrow_left from '@/assets/icons/ic_public_arrow_left.svg?raw'
import ic_public_arrow_right from '@/assets/icons/ic_public_arrow_right.svg?raw'
import ic_public_back from '@/assets/icons/ic_public_back.svg?raw'
import ic_public_brightness from '@/assets/icons/ic_public_brightness.svg?raw'
import ic_public_brightness_filled from '@/assets/icons/ic_public_brightness_filled.svg?raw'
import ic_public_clock from '@/assets/icons/ic_public_clock.svg?raw'
import ic_public_close from '@/assets/icons/ic_public_close.svg?raw'
import ic_public_comments from '@/assets/icons/ic_public_comments.svg?raw'
import ic_public_connection from '@/assets/icons/ic_public_connection.svg?raw'
import ic_public_contacts from '@/assets/icons/ic_public_contacts.svg?raw'
import ic_public_copy from '@/assets/icons/ic_public_copy.svg?raw'
import ic_public_delete from '@/assets/icons/ic_public_delete.svg?raw'
import ic_public_download from '@/assets/icons/ic_public_download.svg?raw'
import ic_public_drawer from '@/assets/icons/ic_public_drawer.svg?raw'
import ic_public_edit from '@/assets/icons/ic_public_edit.svg?raw'
import ic_public_file from '@/assets/icons/ic_public_file.svg?raw'
import ic_public_folder from '@/assets/icons/ic_public_folder.svg?raw'
import ic_public_gps from '@/assets/icons/ic_public_gps.svg?raw'
import ic_public_highlight from '@/assets/icons/ic_public_highlight.svg?raw'
import ic_public_home from '@/assets/icons/ic_public_home.svg?raw'
import ic_public_message from '@/assets/icons/ic_public_message.svg?raw'
import ic_public_more from '@/assets/icons/ic_public_more.svg?raw'
import ic_public_ok from '@/assets/icons/ic_public_ok.svg?raw'
import ic_public_pause from '@/assets/icons/ic_public_pause.svg?raw'
import ic_public_play from '@/assets/icons/ic_public_play.svg?raw'
import ic_public_ring from '@/assets/icons/ic_public_ring.svg?raw'
import ic_public_refresh from '@/assets/icons/ic_public_refresh.svg?raw'
import ic_public_search from '@/assets/icons/ic_public_search.svg?raw'
import ic_public_security from '@/assets/icons/ic_public_security.svg?raw'
import ic_public_send from '@/assets/icons/ic_public_send.svg?raw'
import ic_public_settings from '@/assets/icons/ic_public_settings.svg?raw'
import ic_public_spinner from '@/assets/icons/ic_public_spinner.svg?raw'
import ic_public_time from '@/assets/icons/ic_public_time.svg?raw'
import ic_public_upload from '@/assets/icons/ic_public_upload.svg?raw'
import ic_public_upload_filled from '@/assets/icons/ic_public_upload_filled.svg?raw'
import ic_public_view_list from '@/assets/icons/ic_public_view_list.svg?raw'
import ic_public_worldclock from '@/assets/icons/ic_public_worldclock.svg?raw'

/** HarmonyOS 图标文件名 → SVG 文本（供 HIcon 渲染；key 不带 .svg 后缀） */
export const HARMONY_ICONS = {
  ic_public_add,
  ic_public_albums,
  ic_public_arrow_left,
  ic_public_arrow_right,
  ic_public_back,
  ic_public_brightness,
  ic_public_brightness_filled,
  ic_public_clock,
  ic_public_close,
  ic_public_comments,
  ic_public_connection,
  ic_public_contacts,
  ic_public_copy,
  ic_public_delete,
  ic_public_download,
  ic_public_drawer,
  ic_public_edit,
  ic_public_file,
  ic_public_folder,
  ic_public_gps,
  ic_public_highlight,
  ic_public_home,
  ic_public_message,
  ic_public_more,
  ic_public_ok,
  ic_public_pause,
  ic_public_play,
  ic_public_refresh,
  ic_public_ring,
  ic_public_search,
  ic_public_security,
  ic_public_send,
  ic_public_settings,
  ic_public_spinner,
  ic_public_time,
  ic_public_upload,
  ic_public_upload_filled,
  ic_public_view_list,
  ic_public_worldclock
}

/** Element Plus 图标名 → HarmonyOS 图标文件名（HIcon 的 name 传 Element 名即可自动替换） */
export const ELEMENT_TO_HARMONY = {
  Plus: 'ic_public_add',
  Delete: 'ic_public_delete',
  Clock: 'ic_public_clock',
  MagicStick: 'ic_public_highlight',
  Highlight: 'ic_public_highlight',
  Refresh: 'ic_public_refresh',
  CopyDocument: 'ic_public_copy',
  VideoPlay: 'ic_public_play',
  VideoPause: 'ic_public_pause',
  Location: 'ic_public_gps',
  Loading: 'ic_public_spinner',
  Setting: 'ic_public_settings',
  Back: 'ic_public_back',
  Check: 'ic_public_ok',
  Edit: 'ic_public_edit',
  AlarmClock: 'ic_public_time',
  Bell: 'ic_public_ring',
  Connection: 'ic_public_connection',
  Send: 'ic_public_send',
  Odometer: 'ic_public_home',
  ChatDotRound: 'ic_public_message',
  Earth: 'ic_public_worldclock',
  User: 'ic_public_contacts',
  Reading: 'ic_public_albums',
  ArrowDown: 'ic_public_drawer',
  ArrowUp: 'ic_public_drawer',
  ArrowLeft: 'ic_public_back',
  ArrowRight: 'ic_public_arrow_right',
  Fold: 'ic_public_arrow_left',
  Expand: 'ic_public_arrow_right',
  Sunny: 'ic_public_brightness',
  Moon: 'ic_public_brightness_filled',
  Key: 'ic_public_security',
  Document: 'ic_public_file',
  DataAnalysis: 'ic_public_view_list',
  Close: 'ic_public_close',
  Download: 'ic_public_download',
  Upload: 'ic_public_upload',
  Search: 'ic_public_search',
  MoreFilled: 'ic_public_more',
  UploadFilled: 'ic_public_upload_filled',
  FolderOpened: 'ic_public_folder'
}
