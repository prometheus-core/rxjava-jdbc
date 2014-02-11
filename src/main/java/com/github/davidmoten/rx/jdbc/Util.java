package com.github.davidmoten.rx.jdbc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.apache.commons.io.IOUtils;

import rx.Observable;
import rx.util.functions.Action0;
import rx.util.functions.Func1;
import rx.util.functions.Functions;

/**
 * Utility methods.
 */
public final class Util {

	/**
	 * Logger.
	 */
	private static final Logger log = Logger.getLogger(Util.class);

	static int parametersPerSetCount(String sql) {
		// TODO account for ? characters in string constants
		return countOccurrences(sql, '?');
	}

	private static int countOccurrences(String haystack, char needle) {
		int count = 0;
		for (int i = 0; i < haystack.length(); i++) {
			if (haystack.charAt(i) == needle)
				count++;
		}
		return count;
	}

	static void closeQuietly(PreparedStatement ps) {
		try {
			if (ps != null && !ps.isClosed()) {
				try {
					ps.cancel();
					log.debug("cancelled " + ps);
				} catch (SQLException e) {
					log.debug(e.getMessage());
				}
				ps.close();
				log.debug("closed " + ps);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	static void closeQuietly(Connection connection) {
		try {
			if (connection != null && !connection.isClosed()) {
				connection.close();
				log.debug("closed " + connection);
			}
		} catch (SQLException e) {
			log.debug(e.getMessage());
		}
	}

	static void closeQuietlyIfAutoCommit(Connection connection) {
		try {
			if (connection != null && !connection.isClosed()
					&& connection.getAutoCommit())
				closeQuietly(connection);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	static void commit(Connection connection) {
		if (connection != null)
			try {
				connection.commit();
				log.debug("committed");
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
	}

	static void rollback(Connection connection) {
		if (connection != null)
			try {
				connection.rollback();
				log.debug("rolled back");
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
	}

	static void closeQuietly(ResultSet rs) {
		try {
			if (rs != null && !rs.isClosed()) {
				rs.close();
				log.debug("closed " + rs);
			}
		} catch (SQLException e) {
			log.debug(e.getMessage());
		}
	}

	static boolean isAutoCommit(Connection con) {
		try {
			return con.getAutoCommit();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	public static Func1<Integer, List<Parameter>> TO_EMPTY_PARAMETER_LIST = new Func1<Integer, List<Parameter>>() {
		@Override
		public List<Parameter> call(Integer n) {
			return Collections.emptyList();
		};
	};

	public static <R, S> Func1<R, S> constant(final S s) {
		return new Func1<R, S>() {

			@Override
			public S call(R t1) {
				return s;
			}

		};
	}

	static <T> Func1<ResultSet, T> autoMap(final Class<T> cls) {
		return new Func1<ResultSet, T>() {

			@Override
			public T call(ResultSet rs) {
				return autoMap(rs, cls);
			}
		};
	}

	static <T> T autoMap(ResultSet rs, Class<T> cls) {
		try {
			int n = rs.getMetaData().getColumnCount();
			for (Constructor<?> c : cls.getDeclaredConstructors()) {
				if (n == c.getParameterTypes().length) {
					return autoMap(rs, cls, c);
				}
			}
			throw new RuntimeException("constructor with number of parameters="
					+ n + "  not found in " + cls);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private static <T> T autoMap(ResultSet rs, Class<T> cls, Constructor<?> c) {
		Class<?>[] types = c.getParameterTypes();
		List<Object> list = new ArrayList<Object>();
		for (int i = 0; i < types.length; i++) {
			list.add(autoMap(getObject(rs, types[i], i + 1), types[i]));
		}
		return newInstance(c, list);
	}

	@SuppressWarnings("unchecked")
	private static <T> T newInstance(Constructor<?> c, List<Object> list) {
		try {
			return (T) c.newInstance(list.toArray());
		} catch (InstantiationException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		} catch (IllegalArgumentException e) {
			throw new RuntimeException(e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}

	static Object autoMap(Object o, Class<?> cls) {
		if (o == null)
			return o;
		else if (cls.isAssignableFrom(o.getClass())) {
			return o;
		} else {
			if (o instanceof java.sql.Date) {
				java.sql.Date d = (java.sql.Date) o;
				if (cls.isAssignableFrom(Date.class))
					return new Date(d.getTime());
				else if (cls.isAssignableFrom(Long.class))
					return d.getTime();
				else
					return o;
			} else if (o instanceof java.sql.Timestamp) {
				Timestamp t = (java.sql.Timestamp) o;
				if (cls.isAssignableFrom(Date.class))
					return new Date(t.getTime());
				else if (cls.isAssignableFrom(Long.class))
					return t.getTime();
				else
					return o;
			} else if (o instanceof java.sql.Time) {
				Time t = (java.sql.Time) o;
				if (cls.isAssignableFrom(Date.class))
					return new Date(t.getTime());
				else if (cls.isAssignableFrom(Long.class))
					return t.getTime();
				else
					return o;
			} else if (o instanceof Blob && cls.isAssignableFrom(byte[].class)) {
				return toBytes((Blob) o);
			} else if (o instanceof Clob && cls.isAssignableFrom(String.class)) {
				return toString((Clob) o);
			} else
				return o;
		}
	}

	public static <T> Object getObject(final ResultSet rs, Class<T> cls, int i) {
		try {
			final int type = rs.getMetaData().getColumnType(i);
			// TODO java.util.Calendar support
			// TODO XMLGregorian Calendar support
			if (type == Types.DATE)
				return rs.getDate(i, Calendar.getInstance());
			else if (type == Types.TIME)
				return rs.getTime(i, Calendar.getInstance());
			else if (type == Types.TIMESTAMP)
				return rs.getTimestamp(i, Calendar.getInstance());
			else if (type == Types.CLOB && cls.equals(String.class)) {
				return toString(rs.getClob(i));
			} else if (type == Types.CLOB && Reader.class.isAssignableFrom(cls)) {
				Clob c = rs.getClob(i);
				Reader r = c.getCharacterStream();
				return createFreeOnCloseReader(c, r);
			} else if (type == Types.BLOB && cls.equals(byte[].class)) {
				return toBytes(rs.getBlob(i));
			} else if (type == Types.BLOB
					&& InputStream.class.isAssignableFrom(cls)) {
				final Blob b = rs.getBlob(i);
				final InputStream is = rs.getBlob(i).getBinaryStream();
				return createFreeOnCloseInputStream(b, is);
			} else
				return rs.getObject(i);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private static byte[] toBytes(Blob b) {
		try {
			InputStream is = b.getBinaryStream();
			byte[] result = IOUtils.toByteArray(is);
			is.close();
			b.free();
			return result;
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}

	}

	private static String toString(Clob c) {
		try {
			Reader reader = c.getCharacterStream();
			String result = IOUtils.toString(reader);
			reader.close();
			c.free();
			return result;
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private static InputStream createFreeOnCloseInputStream(final Blob b,
			final InputStream is) {
		return new InputStream() {

			@Override
			public int read() throws IOException {
				return is.read();
			}

			@Override
			public void close() throws IOException {
				try {
					is.close();
				} finally {
					try {
						b.free();
					} catch (SQLException e) {
						log.debug(e.getMessage());
					}
				}
			}
		};
	}

	private static Reader createFreeOnCloseReader(final Clob b, final Reader r) {
		return new Reader() {

			@Override
			public void close() throws IOException {
				try {
					r.close();
				} finally {
					try {
						b.free();
					} catch (SQLException e) {
						log.debug(e.getMessage());
					}
				}
			}

			@Override
			public int read(char[] cbuf, int off, int len) throws IOException {
				return r.read(cbuf, off, len);
			}
		};
	}

	private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

	static void setParameters(PreparedStatement ps, List<Parameter> params)
			throws SQLException {
		for (int i = 1; i <= params.size(); i++) {
			Object o = params.get(i - 1).getValue();
			if (o == null)
				ps.setObject(i, o);
			else {
				Class<?> cls = o.getClass();
				if (Clob.class.isAssignableFrom(cls)) {
					setClob(ps, i, o, cls);
				} else if (Blob.class.isAssignableFrom(cls)) {
					setBlob(ps, i, o, cls);
				} else if (Time.class.isAssignableFrom(cls)) {
					Calendar cal = Calendar.getInstance();
					ps.setTime(i, (Time) o, cal);
				} else if (Timestamp.class.isAssignableFrom(cls)) {
					Calendar cal = Calendar.getInstance();
					ps.setTimestamp(i, (Timestamp) o, cal);
				} else if (java.sql.Date.class.isAssignableFrom(cls)) {
					Calendar cal = Calendar.getInstance();
					ps.setDate(i, (java.sql.Date) o, cal);
				} else
					ps.setObject(i, o);
			}
		}
	}

	private static void setBlob(PreparedStatement ps, int i, Object o,
			Class<?> cls) throws SQLException {
		final InputStream is;
		if (o instanceof byte[]) {
			is = new ByteArrayInputStream((byte[]) o);
		} else if (o instanceof InputStream)
			is = (InputStream) o;
		else
			throw new RuntimeException("cannot insert parameter of type " + cls
					+ " into blob column " + i);
		Blob c = ps.getConnection().createBlob();
		OutputStream os = c.setBinaryStream(1);
		copy(is, os);
		ps.setBlob(i, c);
	}

	private static void setClob(PreparedStatement ps, int i, Object o,
			Class<?> cls) throws SQLException {
		final Reader r;
		if (o instanceof String)
			r = new StringReader((String) o);
		else if (o instanceof Reader)
			r = (Reader) o;
		else
			throw new RuntimeException("cannot insert parameter of type " + cls
					+ " into clob column " + i);
		Clob c = ps.getConnection().createClob();
		Writer w = c.setCharacterStream(1);
		copy(r, w);
		ps.setClob(i, c);
	}

	private static int copy(Reader input, Writer output) {
		try {
			return IOUtils.copy(input, output);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static int copy(InputStream input, OutputStream output) {
		try {
			return IOUtils.copy(input, output);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static final Func1<Reader, String> READER_TO_STRING = new Func1<Reader, String>() {
		@Override
		public String call(Reader r) {
			try {
				return IOUtils.toString(r);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	};

	@SuppressWarnings("unchecked")
	static <T> Observable<T> concatButIgnoreFirstSequence(Observable<?> o1,
			Observable<T> o2) {
		return Observable.concat(
				(Observable<T>) o1.filter(Functions.alwaysFalse()), o2);
	}

	static Action0 toAction0(final Runnable r) {
		return new Action0() {

			@Override
			public void call() {
				r.run();
			}
		};
	}
}