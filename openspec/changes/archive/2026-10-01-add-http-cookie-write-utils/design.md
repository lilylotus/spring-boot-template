## Context

`HttpRequestUtils`（`add-http-request-utils`）已提供入站 `getCookieValue` 的只读能力，其 Non-Goal 明确排除了 `HttpServletResponse` 相关的 Cookie 写入/删除操作。本变更补齐"写"的一侧，是工程内第一次直接使用 `jakarta.servlet.http.HttpServletResponse` 和 `jakarta.servlet.http.Cookie` 的可写 API（`Cookie` 本身在 `HttpRequestUtils` 中已被用于读取，但只调用了 `getName()`/`getValue()`）。

`tomcat-embed-core:10.1.60` 对应 Jakarta Servlet 6.0（Jakarta EE 10），该版本的 `Cookie` 类已移除旧的 `comment`/`version` 字段，改为通用的 `setAttribute(String, String)`/`getAttribute(String)` 扩展属性机制；`SameSite` 没有专门的 setter，按 Servlet 容器（Tomcat）约定的属性名 `SameSite`、取值 `Strict`/`Lax`/`None` 通过 `setAttribute` 设置。

## Goals / Non-Goals

**Goals:**
- 提供统一入口写出响应 Cookie，覆盖 `maxAge`/`path`/`domain`/`secure`/`httpOnly`/`SameSite` 全部常用属性。
- 提供合理且偏安全的默认值（`httpOnly=true`），减少因遗漏属性导致的安全隐患。
- 在构造阶段拦截已知会被浏览器直接丢弃的非法组合（`SameSite=None` 但未 `Secure`），而不是产出一个"构造成功但浏览器不认可"的 Cookie。
- 不引入新的第三方依赖。

**Non-Goals:**
- 不提供删除 Cookie 的专用方法（如 `deleteCookie(response, name)`）；删除 Cookie 本质是写入一个 `maxAge=0` 的同名 Cookie，调用方可直接用 `setCookie(response, name, "", CookieOptions.builder().maxAge(0).build())` 实现，暂不封装独立入口（YAGNI，待出现明确重复调用场景再补充）。
- 不做 Cookie 值的编码/转义（如 URL 编码）；`jakarta.servlet.http.Cookie` 对值中的非法字符本身有限制，调用方需自行保证 `value` 符合 Cookie 值的合法字符集，或自行编码后再传入。
- 不提供批量设置多个 Cookie 的入口；`setCookie` 单次只处理一个 Cookie，多个 Cookie 由调用方多次调用。
- 不修改 `HttpRequestUtils` 的只读职责，也不在同一个类中混合读/写方法。

## Decisions

- **新增独立的 `HttpResponseUtils` 类，而不是往 `HttpRequestUtils` 里加方法**：`HttpRequestUtils` 的类级 Javadoc 和既有设计明确将其定位为"入站请求读取"工具类（方法签名统一以 `HttpServletRequest` 为入参），写 Cookie 操作的入参是 `HttpServletResponse`，语义和依赖类型都不同，混入同一个类会破坏既有的单一职责边界，故新增对应的 `HttpResponseUtils`。
- **用 `CookieOptions` Builder 封装属性，而不是一个 7+ 参数的方法**：`setCookie(response, name, value, maxAge, path, domain, secure, httpOnly, sameSite)` 参数过多、调用时容易错位且大多数调用只需设置其中 1-2 个非默认属性；Builder 模式可以只显式设置需要覆盖的属性，其余使用合理默认值，可读性更好，后续新增属性（如未来的 `Partitioned` 属性）也不会破坏已有调用方的方法签名。
- **`name`/`value` 从 `CookieOptions` 中拆出，作为 `setCookie` 的独立方法参数**：`name`/`value` 是每次调用几乎总要变化的必填信息，和 `maxAge`/`path`/`domain`/`secure`/`httpOnly`/`sameSite` 这类"通常保持默认、偶尔按场景覆盖"的属性在变化频率和必填性上性质不同；拆开后 `CookieOptions.builder()` 不带参数即可拿到一份纯粹的"默认属性集合"，便于复用同一份 `CookieOptions` 给多个不同 `name`/`value` 的 Cookie（如批量写入多个业务含义不同但过期策略相同的 Cookie），也让方法签名在调用处直接可见"这次写的是哪个 Cookie"而不必进入 `CookieOptions` 内部查看。
- **`SameSite` 用枚举而不是裸 `String`**：避免调用方传入拼写错误或大小写不一致的字符串（Cookie 属性值规范要求 `Strict`/`Lax`/`None` 首字母大写），枚举在编译期即可约束合法取值，内部转换时固定输出规范大小写。
- **`SameSite=NONE` 必须搭配 `secure=true`，否则 `build()` 抛 `IllegalStateException`**：这是 Chrome 等主流浏览器自 2020 年起强制的规则（没有 `Secure` 属性的 `SameSite=None` Cookie 会被浏览器直接丢弃，不会写入也不会报错，调用方很难察觉问题）。在工具类内提前校验并报错，比"看起来成功但线上排查很久才发现 Cookie 没生效"更可取。
- **`httpOnly` 默认值为 `true`，`secure`/`domain`/`sameSite` 默认不设置（即维持浏览器默认行为）**：`HttpOnly` 几乎总是期望开启（防止脚本读取 Cookie），默认开启更安全；`secure`/`domain`/`sameSite` 的正确值依赖具体部署环境（是否全站 HTTPS、是否需要跨子域、跨站请求的实际需求），不适合在工具类里假设统一默认值，由调用方按场景显式设置。
- **参数校验与异常语义**：`response` 为 `null`、`options` 为 `null`，或 `setCookie` 的 `name` 为 `null`/空白字符串，均抛出 `IllegalArgumentException`，与 `HttpRequestUtils` 对必需参数的处理方式一致；`name` 校验放在 `setCookie` 方法本身（而不是 `CookieOptions.builder()`），因为 `name`/`value` 已拆出，不再经过 `CookieOptions` 的构造流程。`SameSite=None` 缺少 `Secure` 这一类"参数组合在语义上不自洽"的情况，抛出 `IllegalStateException`（遵循 `Cookie.setMaxAge`/`Builder` 等 JDK 惯例，用 `IllegalStateException` 区分于"单个参数本身非法"的 `IllegalArgumentException`）。
- **不提供读取/修改已设置 Cookie 的能力**：`setCookie` 是一次性写入操作，调用 `response.addCookie(cookie)` 后即交由 Servlet 容器序列化为 `Set-Cookie` 响应头，工具类本身不保留状态、不提供查询已写入内容的接口（与 `HttpServletResponse` 本身的能力边界一致）。

## Risks / Trade-offs

- **`Cookie#setAttribute` 设置 `SameSite` 依赖 Servlet 容器实现（Tomcat）对该属性名的识别**：这是 Tomcat 10.1（Jakarta Servlet 6.0）的既定支持方式，但如果未来更换 Servlet 容器实现，需要重新确认该容器是否识别同样的属性名写法。
- **`CookieOptions` 是本变更新增的小型值对象，增加了一个概念**：相比直接暴露多参数方法，调用方需要多学习一个 Builder API；本变更认为可读性和未来扩展性的收益超过这点学习成本。
