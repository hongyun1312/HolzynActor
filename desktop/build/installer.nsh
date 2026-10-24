; ============================================================
; HolzynActor 自定义 NSIS 安装脚本（electron-builder include）
; ------------------------------------------------------------
; 由 electron-builder 自动加载（默认 include 路径 build/installer.nsh），
; 同时作用于安装器（installer）与卸载器（uninstaller）两个脚本。
;
; 提供的三项能力（v1.1.0-beta.2）：
;   1. 「选择附加任务」页：创建桌面快捷方式 / 创建开始菜单快捷方式，
;      两个勾选框均默认勾选，可分别取消；
;   2. 安装过程中实时输出中文分阶段日志并同步写入安装目录下的
;      install.log（UTF-8 with BOM）。修复：原版本在「正在安装」页用
;      nsDialogs::CreateControl 绘制向导内日志控件，而该页没有先调用
;      nsDialogs::Create 建立活动对话框，nsDialogs 内部持有上一页销毁后的
;      悬空句柄，控件样式应用（SetWindowLong）触发 USER32 访问违规导致
;      安装器闪退（本机与新装机均复现）；自修复版起不再绘制向导内日志控件，
;      日志以 install.log + 详情视图（DetailPrint）呈现；
;   3. 卸载时（卸载欢迎页）询问是否同时删除本地数据
;      （%LOCALAPPDATA%\HolzynActor 下的模型/世界数据/配置，默认保留）。
;
; 依赖说明：
;   - 本文件在 electron-builder 生成的脚本最顶部被 include（先于模板），
;     因此这里显式引入 MUI2（含 nsDialogs / LogicLib / WinMessages），
;     后续模板再次 include 时会被各自的内建守卫跳过；
;   - ${PRODUCT_NAME}/${VERSION}/${SHORTCUT_NAME}/${APP_ID}/${APP_DESCRIPTION}
;     等由 electron-builder 通过 makensis -D 注入，此处可直接使用；
;   - ${APP_EXECUTABLE_FILENAME}/${UNINSTALL_FILENAME} 由模板 common.nsh 定义
;     （macro 体在插入时才展开），故仅在自定义 macro 内部引用，不在函数体内直接使用；
;   - 安装器与卸载器是两个独立编译的脚本（BUILD_UNINSTALLER 区分），
;     为避免 -WX（warnings-as-errors）下“未引用函数/变量”告警导致构建失败，
;     安装器侧与卸载器侧的代码/变量严格按 BUILD_UNINSTALLER 分区。
; ============================================================

!include "MUI2.nsh"

; FileFunc：只用到 ${GetParameters} / ${GetOptions}（判断 --updated，见 holzyn.AdditionalTasksCreate）。
; 为什么不用 electron-builder 的 ${isUpdated}：它展开成 StdUtils 插件调用，而本自定义 include
; 在 electron-builder 25.x 生成的脚本里排在 !addplugindir 之前——插件目录还没注册就解析插件，
; 编译期直接报「Plugin not found, cannot call StdUtils::TestParameter」导致安装器构建失败
; （2026-10-23 复现，冷/热缓存都一样）。改用 NSIS 自带 FileFunc 判断，不再依赖插件注册顺序。
!include "FileFunc.nsh"

!ifndef BUILD_UNINSTALLER
; ############################################################
; 安装器（installer）侧
; ############################################################

Var holzyn.CreateDesktop       ; 附加任务页：是否创建桌面快捷方式（"1"=是，默认）
Var holzyn.CreateMenu          ; 附加任务页：是否创建开始菜单快捷方式（默认"1"）
Var holzyn.ChkDesktop          ; 桌面快捷方式勾选框句柄
Var holzyn.ChkMenu             ; 开始菜单快捷方式勾选框句柄
Var holzyn.Dialog              ; nsDialogs 页面句柄

; ============================================================
; 1) 「选择附加任务」页
;    —— 通过 electron-builder 的 customPageAfterChangeDir 钩子插入，
;       位于“选择安装位置”之后、“正在安装”页之前。
; ============================================================
!macro customPageAfterChangeDir
  ; 让紧随其后的 MUI_PAGE_INSTFILES 在显示时调用我们的日志初始化函数
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW holzyn.InstFilesShow
  Page custom holzyn.AdditionalTasksCreate holzyn.AdditionalTasksLeave
!macroend

Function holzyn.AdditionalTasksCreate
  ; 更新/覆盖安装时跳过该页（沿用既有快捷方式，避免打扰自动更新）
  ; electron-updater 以 --updated 调用安装器 → 自行判断，避免 StdUtils 插件顺序问题（见文件顶部说明）
  Push $R9
  Push $R8
  ${GetParameters} $R9
  ClearErrors
  ${GetOptions} "$R9" "--updated" $R8
  Pop $R8
  Pop $R9
  ${IfNot} ${Errors}
    Abort
  ${EndIf}
  !insertmacro MUI_HEADER_TEXT "选择附加任务" "请选择要创建的快捷方式"
  nsDialogs::Create 1018
  Pop $holzyn.Dialog
  ${If} $holzyn.Dialog == error
    Abort
  ${EndIf}
  ${NSD_CreateLabel} 0 0 100% 24u "安装位置：$INSTDIR"
  Pop $0
  ${NSD_CreateCheckBox} 0 32u 100% 24u "创建桌面快捷方式(&D)"
  Pop $holzyn.ChkDesktop
  ${NSD_CreateCheckBox} 0 62u 100% 24u "创建开始菜单快捷方式(&S)"
  Pop $holzyn.ChkMenu
  ; 恢复用户此前选择（默认均勾选）
  ${If} $holzyn.CreateDesktop == "0"
    ${NSD_Uncheck} $holzyn.ChkDesktop
  ${Else}
    ${NSD_Check} $holzyn.ChkDesktop
  ${EndIf}
  ${If} $holzyn.CreateMenu == "0"
    ${NSD_Uncheck} $holzyn.ChkMenu
  ${Else}
    ${NSD_Check} $holzyn.ChkMenu
  ${EndIf}
  nsDialogs::Show
FunctionEnd

Function holzyn.AdditionalTasksLeave
  ${NSD_GetState} $holzyn.ChkDesktop $0
  StrCpy $holzyn.CreateDesktop $0
  ${NSD_GetState} $holzyn.ChkMenu $0
  StrCpy $holzyn.CreateMenu $0
FunctionEnd

; ============================================================
; 2) 安装进度日志
;    —— MUI_PAGE_INSTFILES 显示时输出初始日志，安装节执行期间通过
;       AppendLog 追加中文日志并写 install.log（UTF-8 with BOM）+ DetailPrint。
;       修复说明：不再创建向导内日志控件（nsDialogs::CreateControl 在
;       「正在安装」页无活动 nsDialogs 对话框时因悬空句柄崩溃，见文件头注释）。
; ============================================================

; 「正在安装」页显示时的日志初始化（v1.1.0-beta.2 修复版）
Function holzyn.InstFilesShow
  ; 输出初始日志（写入 install.log 并 DetailPrint；静默安装同样生效）
  CreateDirectory "$INSTDIR"
  Push "开始安装 ${PRODUCT_NAME} v${VERSION}"
  Call holzyn.AppendLog
  Push "正在解压并安装应用文件，请稍候..."
  Call holzyn.AppendLog
FunctionEnd

; 追加一条日志：写入 install.log + DetailPrint（修复版不再有向导内日志区）
Function holzyn.AppendLog
  Exch $0                                       ; $0 = 消息文本
  Push $0
  Call holzyn.GetTimestamp                      ; $9 = "[MM-DD HH:MM:SS] "
  Pop $1                                        ; $1 = 消息（GetTimestamp 已改写 $0-$8）
  StrCpy $0 "$9$1"                              ; $0 = 带时间戳的完整行
  ; 写入 install.log（UTF-8 with BOM，跨系统编码环境均可用）
  Push $0
  Call holzyn.WriteLogUTF8
  ; DetailPrint（配合 /LOG 与详情视图排查）
  DetailPrint "$0"
  Pop $0
FunctionEnd

; 生成时间戳 "[MM-DD HH:MM:SS] "，返回于 $9（会改写 $0-$8，调用方需自行保护）。
; 注：SYSTEMTIME 字段顺序为 wYear,wMonth,wDayOfWeek,wDay,wHour,wMinute,wSecond,wMilliseconds，
;     故 $2=月、$3=周几、$4=日、$5=时、$6=分、$7=秒（修复：原误用 $3 导致日志日显示为周几）。
Function holzyn.GetTimestamp
  System::Call "*(&i2,&i2,&i2,&i2,&i2,&i2,&i2,&i2)p.r0"
  System::Call "kernel32::GetLocalTime(p r0)"
  System::Call "*$0(&i2.r1,&i2.r2,&i2.r3,&i2.r4,&i2.r5,&i2.r6,&i2.r7,&i2.r8)"
  System::Free $0
  StrCpy $9 "["
  IntFmt $1 "%02i" $2
  StrCpy $9 "$9$1-"
  IntFmt $1 "%02i" $4
  StrCpy $9 "$9$1 "
  IntFmt $1 "%02i" $5
  StrCpy $9 "$9$1:"
  IntFmt $1 "%02i" $6
  StrCpy $9 "$9$1:"
  IntFmt $1 "%02i" $7
  StrCpy $9 "$9$1] "
FunctionEnd

; 以 UTF-8(with BOM) 追加写入 $INSTDIR\install.log
Function holzyn.WriteLogUTF8
  Exch $0
  StrCpy $0 "$0$\r$\n"
  StrLen $2 $0
  IntOp $2 $2 * 4
  IntOp $2 $2 + 16
  System::Alloc $2
  Pop $1
  System::Call "kernel32::WideCharToMultiByte(i 65001, i 0, w r0, i -1, p r1, i $2, i 0, i 0) i.r3"
  ${IfNot} ${FileExists} "$INSTDIR\install.log"
    FileOpen $8 "$INSTDIR\install.log" "w"
    FileWriteByte $8 0xEF
    FileWriteByte $8 0xBB
    FileWriteByte $8 0xBF
  ${Else}
    FileOpen $8 "$INSTDIR\install.log" "a"
    FileSeek $8 0 END
  ${EndIf}
  IntOp $4 $3 - 1                               ; 需写入的字节数（去掉结尾 NUL）
  StrCpy $6 0
  loop:
    IntCmp $6 $4 done
    System::Call "*$1(b.r7)"
    FileWriteByte $8 $7
    IntOp $1 $1 + 1
    IntOp $6 $6 + 1
    Goto loop
  done:
    FileClose $8
    System::Free $1
    Pop $0
FunctionEnd

; ============================================================
; 3) 安装收尾（customInstall，运行于安装节末尾）
;    —— 按附加任务页勾选状态处理快捷方式，并创建开始菜单卸载入口
; ============================================================
!macro customInstall
  ; 模板已按 createDesktopShortcut/createStartMenuShortcut 配置创建快捷方式，
  ; 此处按用户勾选状态移除未勾选的那项（勾选则保留）。
  Push "正在创建快捷方式..."
  Call holzyn.AppendLog
  ${If} $holzyn.CreateDesktop == "0"
    Delete "$DESKTOP\${SHORTCUT_NAME}.lnk"
    ClearErrors
    Push "已跳过桌面快捷方式"
    Call holzyn.AppendLog
  ${Else}
    Push "已创建桌面快捷方式"
    Call holzyn.AppendLog
  ${EndIf}
  ${If} $holzyn.CreateMenu == "0"
    Delete "$SMPROGRAMS\${SHORTCUT_NAME}.lnk"
    ClearErrors
    StrCpy $launchLink "$INSTDIR\${APP_EXECUTABLE_FILENAME}"
    Push "已跳过开始菜单快捷方式"
    Call holzyn.AppendLog
  ${Else}
    Push "已创建开始菜单快捷方式"
    Call holzyn.AppendLog
  ${EndIf}

  ; 开始菜单「卸载 HolzynActor」入口（卸载功能的固定入口之一）
  Push "正在创建开始菜单卸载入口..."
  Call holzyn.AppendLog
  CreateDirectory "$SMPROGRAMS\${SHORTCUT_NAME}"
  CreateShortCut "$SMPROGRAMS\${SHORTCUT_NAME}\卸载 ${SHORTCUT_NAME}.lnk" "$INSTDIR\${UNINSTALL_FILENAME}" "" "$INSTDIR\${APP_EXECUTABLE_FILENAME}" 0

  Push "安装完成！"
  Call holzyn.AppendLog
!macroend

!else
; ############################################################
; 卸载器（uninstaller）侧
; ############################################################

Var holzyn.UninstallDeleteData ; 卸载页：是否删除本地数据（默认"0"=保留）
Var holzyn.ChkDeleteData       ; 卸载页：删除数据勾选框句柄
Var holzyn.Dialog              ; nsDialogs 页面句柄

; ============================================================
; 4) 卸载器：询问是否删除本地数据
;    —— 以 customUnWelcomePage 替换默认卸载欢迎页，
;       新增“是否同时删除本地数据”勾选框（默认不勾选=保留）
; ============================================================
!macro customUnWelcomePage
  Page custom holzyn.UnWelcomeCreate holzyn.UnWelcomeLeave
!macroend

Function holzyn.UnWelcomeCreate
  !insertmacro MUI_HEADER_TEXT "卸载 HolzynActor" "请选择卸载选项"
  nsDialogs::Create 1018
  Pop $holzyn.Dialog
  ${If} $holzyn.Dialog == error
    Abort
  ${EndIf}
  ${NSD_CreateLabel} 0 0 100% 52u "即将卸载 HolzynActor。$\r$\n默认将保留你的本地数据（模型、世界数据、配置等，位于 %LOCALAPPDATA%\HolzynActor）。"
  Pop $0
  ${NSD_CreateCheckBox} 0 60u 100% 24u "同时删除本地数据（模型、世界数据、配置等）(&D)"
  Pop $holzyn.ChkDeleteData
  ${If} $holzyn.UninstallDeleteData == "1"
    ${NSD_Check} $holzyn.ChkDeleteData
  ${EndIf}
  nsDialogs::Show
FunctionEnd

Function holzyn.UnWelcomeLeave
  ${NSD_GetState} $holzyn.ChkDeleteData $0
  StrCpy $holzyn.UninstallDeleteData $0
FunctionEnd

; ============================================================
; 5) 卸载收尾（customUnInstall，运行于卸载节末尾）
;    —— 按卸载页勾选状态删除本地数据；清理开始菜单卸载入口
; ============================================================
!macro customUnInstall
  ${If} $holzyn.UninstallDeleteData == "1"
    RMDir /r "$LOCALAPPDATA\HolzynActor"
    RMDir /r "$APPDATA\HolzynActor"
  ${EndIf}
  ; 清理我们在安装时创建的开始菜单卸载入口
  Delete "$SMPROGRAMS\${SHORTCUT_NAME}\卸载 ${SHORTCUT_NAME}.lnk"
  ClearErrors
  RMDir "$SMPROGRAMS\${SHORTCUT_NAME}"
!macroend

!endif
