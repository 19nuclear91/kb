# 企业浏览器登录供应商契约

核对日期：2026-10-17。仅访问公开官方说明和官方 SDK 源码，未调用认证或成员接口，未验证真实凭据、响应及权限。页面不可提取处明确保留证据边界。三家均使用授权码，不验证不存在的标准 OIDC ID token，不自动重试换码，不使用工作台内免登。

## 钉钉

[官方浏览器授权说明](https://open-dingtalk.github.io/developerpedia/docs/develop/permission/token/browser/get_user_app_token_browser/) 可读取正文：`login.dingtalk.com/oauth2/auth`，scope `openid corpid`，回调字段 **authCode**；POST `api.dingtalk.com/v1.0/oauth2/userAccessToken`，请求 clientId/clientSecret/code/grantType，响应 accessToken/corpId。选择组织必须匹配配置企业。用户凭证 GET `/v1.0/contact/users/me`，`x-acs-dingtalk-access-token` 请求头，取 unionId。

应用凭证 POST `/v1.0/oauth2/accessToken`，appKey/appSecret，取 accessToken/expireIn。POST `oapi.dingtalk.com/topapi/user/getbyunionid` 取企业 userid，再 POST `/topapi/v2/user/get`，要求 userid/unionid 一致及 active=true。60121 视为明确无成员，其余无法证明成员状态的错误安全失败。corpId 不能代替成员读取。

[unionId 映射官方页](https://open.dingtalk.com/document/orgapp-server/query-a-user-by-the-union-id)、[成员详情官方页](https://open.dingtalk.com/document/orgapp/query-user-details) 本轮仅取得标题，字段未实测。最小权限类别为个人通讯录基本信息（官方浏览器说明列举 `Contact.User.Read`）及企业内 unionId 映射、成员详情读取；须在应用权限面板开放对应接口和成员范围，不请求手机号、邮箱读取。所选浏览器官方说明未列 PKCE，故仅飞书启用 S256；钉钉依赖服务端机密、严格 state 和浏览器关联。

## 企业微信

[新版网页登录官方入口](https://developer.work.weixin.qq.com/document/path/98151) 本轮工具不可访问正文。使用 `login.work.weixin.qq.com/wwlogin/sso/login`，login_type=CorpApp、appid=CorpId、agentid、redirect_uri、state；回调 code。配置为企业自建应用的 Secret，不用网页工作台 JS 免登。

GET `qyapi.weixin.qq.com/cgi-bin/gettoken`（corpid/corpsecret），应用 access_token 仅供该应用使用。GET `/cgi-bin/auth/getuserinfo`（access_token/code），只接受成员 userid，不用个人 openid。GET `/cgi-bin/user/get`（access_token/userid），要求 userid 一致及 status=1；明确非成员或停用返回403，无 status 或缺权限返回503，40029/40163 拒绝已失效授权码。参考官方[访问用户身份](https://developer.work.weixin.qq.com/document/path/91023)、[读取成员](https://developer.work.weixin.qq.com/document/path/90196)、[应用令牌](https://developer.work.weixin.qq.com/document/path/91039)。这些接口字段按独立适配器约束编写，未以真实响应验证。

最小配置为企业自建应用登录资格、相应可见成员范围和读取成员基本状态权限，应用 Secret 必须属于配置 AgentId；不申请敏感个人字段。接口读取不出成员或状态时拒绝，不以配置范围代替实际核验。所选说明未获得 PKCE 字段依据，暂不发送伪 PKCE 参数。

## 飞书

[授权码](https://open.feishu.cn/document/authentication-management/access-token/obtain-oauth-code)、[用户令牌](https://open.feishu.cn/document/authentication-management/access-token/get-user-access-token)、[用户信息](https://open.feishu.cn/document/server-docs/authentication-management/login-state-management/get)、[成员读取](https://open.feishu.cn/document/server-docs/contact-v3/user/get) 本轮仅取得页面标题。采用 `accounts.feishu.cn/open-apis/authen/v1/authorize`，client_id/response_type=code/redirect_uri/state，S256 code_challenge；POST `open.feishu.cn/open-apis/authen/v2/oauth/token` 发送 code_verifier，取 access_token，不保存 refresh_token。

GET `/open-apis/authen/v1/user_info` Bearer 用户令牌，tenant_key 必须精确匹配，open_id 必须存在。POST `/open-apis/auth/v3/tenant_access_token/internal` 用 app_id/app_secret 取 tenant_access_token/expire，再 GET `/open-apis/contact/v3/users/{open_id}?user_id_type=open_id`。要求 open_id 一致、is_activated=true、is_frozen/is_resigned/is_exited=false。[飞书官方 SDK 的 UserStatus](https://github.com/larksuite/oapi-sdk-python/blob/v2_main/lark_oapi/api/contact/v3/model/user_status.py) 可读取这几个布尔字段定义，但不构成真实接口兼容性证据。字段缺失返回503，明确停用返回403。

应用最小权限类别为当前成员接口的通讯录用户基本信息与状态读取，并配置该接口所需的可见范围；不请求手机、邮箱或 offline_access。本轮未提取官方页面的权限表，未核对完整 permission code 清单，部署输入以链接的成员接口当前权限要求为准，不能把这里的类别描述当成权限配置已验证。应用内 open_id 的绑定作用域含 App ID，不跨应用复用。租户不符拒绝，手机号、邮箱、名称均不参与内部账号匹配。所有 token 只在服务端处理，个人身份获取成功不代表本平台登录成功。
