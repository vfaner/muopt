package com.qqmu.muopt.service.connection;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 让 {@link java.sql.DriverManager} 能使用“非系统类加载器”加载的驱动（移植自 synctool）。
 *
 * <p>DriverManager 拒绝使用调用方类加载器看不到的驱动类，而运行时从外部 jar 加载的驱动
 * 正是这种情况。Shim 本身位于应用类加载器上，注册到 DriverManager 后把调用全部委托给
 * 子加载器中的真实驱动，即可绕过该校验。
 */
public class DriverShim implements Driver {

    private final Driver delegate;

    public DriverShim(Driver delegate) {
        this.delegate = delegate;
    }

    public Driver getDelegate() {
        return delegate;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return delegate.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return delegate.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public String toString() {
        return "DriverShim[" + delegate.getClass().getName() + "]";
    }
}
