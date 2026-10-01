// 动态加载侧边栏菜单，所有页面共用，保持一致
document.addEventListener('DOMContentLoaded', function() {
    // 获取当前页面名称，从URL或路径中提取
    const path = window.location.pathname;
    let currentPage = 'manual'; // 默认

    if (path.includes('auto.html')) currentPage = 'auto';
    else if (path.includes('explain.html')) currentPage = 'explain';
    else if (path.includes('datasource.html')) currentPage = 'datasource';
    else if (path.includes('ai.html')) currentPage = 'ai';
    else if (path.includes('about.html')) currentPage = 'about';
    else if (path === '/' || path.includes('manual.html')) currentPage = 'manual';

    // 创建侧边栏HTML
    const sidebarHtml = `
        <div class="sidebar-header">
            <h1>MuOpt <span style="font-size:14px;font-weight:normal;">v1.0.0</span></h1>
            <p>沐优 - SQL优化助手</p>
        </div>
        <nav class="menu">
            <a href="/manual.html" class="menu-item ${currentPage === 'manual' ? 'active' : ''}">
                <span class="menu-icon">✏️</span><span>手工优化</span>
            </a>
            <a href="/auto.html" class="menu-item ${currentPage === 'auto' ? 'active' : ''}">
                <span class="menu-icon">🔍</span><span>扫描优化</span>
            </a>
            <a href="/explain.html" class="menu-item ${currentPage === 'explain' ? 'active' : ''}">
                <span class="menu-icon">📊</span><span>SQL执行计划</span>
            </a>
            <a href="/datasource.html" class="menu-item ${currentPage === 'datasource' ? 'active' : ''}">
                <span class="menu-icon">🔗</span><span>数据源配置</span>
            </a>
            <a href="/ai.html" class="menu-item ${currentPage === 'ai' ? 'active' : ''}">
                <span class="menu-icon">🤖</span><span>AI模型配置</span>
            </a>
            <a href="/about.html" class="menu-item ${currentPage === 'about' ? 'active' : ''}">
                <span class="menu-icon">ℹ️</span><span>关于软件</span>
            </a>
        </nav>
    `;

    // 注入到sidebar元素
    const sidebarElement = document.getElementById('sidebar');
    if (sidebarElement) {
        sidebarElement.innerHTML = sidebarHtml;
    }
});
