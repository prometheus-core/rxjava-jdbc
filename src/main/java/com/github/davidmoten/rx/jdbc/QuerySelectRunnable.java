package com.github.davidmoten.rx.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import rx.Observer;

class QuerySelectRunnable implements Runnable, Cancellable {

	private static final Logger log = Logger
			.getLogger(QuerySelectRunnable.class);

	private final Object connectionLock = new Object();
	private volatile Connection con;
	private volatile PreparedStatement ps;
	private volatile ResultSet rs;
	private final QuerySelect query;
	private final List<Parameter> params;
	private final Observer<? super ResultSet> o;
	private final AtomicBoolean keepGoing = new AtomicBoolean(true);

	QuerySelectRunnable(QuerySelect query, List<Parameter> params,
			Observer<? super ResultSet> o) {
		this.query = query;
		this.params = params;
		this.o = o;
	}

	@Override
	public void run() {
		try {

			connectAndPrepareStatement();

			executeQuery();

			while (keepGoing.get()) {
				processRow();
			}

			complete();

		} catch (Exception e) {
			handleException(e);
		}
	}

	private void connectAndPrepareStatement() throws SQLException {
		log.debug(query.context().connectionProvider());
		synchronized (connectionLock) {
			if (keepGoing.get()) {
				con = query.context().connectionProvider().get();
				ps = con.prepareStatement(query.sql());
				Util.setParameters(ps, params);
			}
		}
	}

	private void executeQuery() throws SQLException {
		rs = ps.executeQuery();
		log.debug("executed ps=" + ps);
	}

	private void processRow() throws SQLException {
		synchronized (connectionLock) {
			if (rs.next()) {
				log.debug("onNext");
				o.onNext(rs);
			} else
				keepGoing.set(false);
		}
	}

	private void complete() {
		log.debug("onCompleted");
		o.onCompleted();
		synchronized (connectionLock) {
			close();
		}
	}

	private void handleException(Exception e) {
		log.debug("onError: " + e.getMessage());
		o.onError(e);
	}

	private void close() {
		Util.closeQuietly(rs);
		Util.closeQuietly(ps);
		Util.closeQuietlyIfAutoCommit(con);
	}

	@Override
	public void cancel() {
		// will be called from another Thread to the run method so concurrency
		// controls are essential.
		synchronized (connectionLock) {
			keepGoing.set(false);
			close();
		}
	}

}