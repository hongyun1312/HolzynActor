// 本地个人账户 API（替代原登录/鉴权接口；本地单用户模式）
import http from './http'

/**
 * 获取本地账户（不存在时后端惰性创建空档案返回；含 onboarded 标记）。
 * @returns {Promise<object>} LocalAccountVO
 */
export const fetchAccount = () => http.get('/api/local-account')

/**
 * 保存/更新本地账户（首次向导提交与设置页编辑共用；各字段选填，onboarded=1 视为完成向导）。
 * @param {object} data 账户内容 { nickname, avatarUrl, signature, identity, occupation, hobbies, taboos, profileText, onboarded }
 * @returns {Promise<object>} LocalAccountVO
 */
export const saveAccount = (data) => http.put('/api/local-account', data)

/**
 * 单独标记首次设置向导完成。
 * @returns {Promise<object>} LocalAccountVO
 */
export const markAccountOnboarded = () => http.post('/api/local-account/onboarded')

/**
 * 会话信息（替代原 /api/auth/me）：返回 { onboarded, account }。
 * @returns {Promise<{onboarded:boolean, account:object}>}
 */
export const fetchAccountMe = () => http.get('/api/local-account/me')
