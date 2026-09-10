/**
 * 页面状态暂存：菜单之间是整页跳转，跳转/F5 后 Vue 实例重建，
 * 输入与结果会丢失。用 sessionStorage 在同一标签页内保留各页状态
 * （关闭标签页自动清除）；超出存储配额时静默失败，不影响页面功能。
 */
const PageState = {
    PREFIX: 'sqlopt:state:',

    /** 读取并反序列化；不存在或解析失败返回 null */
    get(key) {
        try {
            const raw = sessionStorage.getItem(this.PREFIX + key);
            return raw ? JSON.parse(raw) : null;
        } catch (e) {
            return null;
        }
    },

    /** 序列化写入；超配额等异常静默吞掉 */
    set(key, value) {
        try {
            sessionStorage.setItem(this.PREFIX + key, JSON.stringify(value));
            return true;
        } catch (e) {
            return false;
        }
    },

    remove(key) {
        try {
            sessionStorage.removeItem(this.PREFIX + key);
        } catch (e) { /* 忽略 */ }
    },

    /** 格式化时间戳用于"上次结果"提示 */
    formatTime(ts) {
        if (!ts) return '';
        try {
            return new Date(ts).toLocaleString();
        } catch (e) {
            return '';
        }
    }
};
