/*
 * Databinder: a simple bridge from Wicket to Hibernate
 * Copyright (C) 2006  Nathan Hamblen nathan@technically.us
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.databinder.auth.hib;

/**
 * Holds IUser identifier for signed in users. Remembering the user with a browser cookie
 * allows that user to bypass login for the length of time specified in getSignInCookieMaxAge().
 * <p> In general the semantics here expect users to have a username and password, though the 
 * IUser interface itself does not require it. Use your <tt>AuthDataApplication</tt> subclass to specify
 * a user class and criteria builder as needed.</p>
 */
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

import javax.servlet.http.Cookie;

import net.databinder.auth.IAuthSession;
import net.databinder.auth.IAuthSettings;
import net.databinder.auth.data.IUser;
import net.databinder.hib.DataRequestCycle;
import net.databinder.hib.Databinder;
import net.databinder.models.hib.HibernateObjectModel;

import org.apache.wicket.Application;
import org.apache.wicket.Request;
import org.apache.wicket.RequestCycle;
import org.apache.wicket.WicketRuntimeException;
import org.apache.wicket.protocol.http.WebApplication;
import org.apache.wicket.protocol.http.WebResponse;
import org.apache.wicket.protocol.http.WebSession;
import org.apache.wicket.util.time.Duration;
import org.hibernate.Criteria;
import org.hibernate.NonUniqueResultException;

public class AuthDataSession extends WebSession implements IAuthSession {
	/** Effective signed in state. */
	private Serializable userId;
	private static final String CHARACTER_ENCODING = "UTF-8";

	/**
	 * Initialize new session.
	 * @see WebApplication
	 */
	public AuthDataSession(Request request) {
		super(request);
	}
	
	/**
	 * @return current session casted to AuthDataSession
	 * @throws ClassCastException if available session is not of this class
	 */
	public static AuthDataSession get() {
		return (AuthDataSession) WebSession.get();
	}
	
	/**
	 * @return IUser object for current user, or null if none signed in.
	 */
	public IUser getUser() {
		if  (isSignedIn()) {
			IUser user = getUser(userId);
			return user;
		}
		return null;
	}
	
	/**
	 * @return model for current user
	 */
	public HibernateObjectModel getUserModel() {
		IAuthSettings app = (IAuthSettings)getApplication();
		return isSignedIn() ? new HibernateObjectModel(app.getUserClass(), userId) : null;
	}
	
	/**
	 * @return length of time sign-in cookie should persist, defined here as one month
	 */
	protected Duration getSignInCookieMaxAge() {
		return Duration.days(31);
	}
	
	/**
	 * Determine if user is signed in, or can be via cookie.
	 * @return true if signed in or cookie sign in is possible and successful
	 */
	public boolean isSignedIn() {
		if (userId == null && cookieSignInSupported())
			cookieSignIn();
		return userId != null; 
	}
	
	/** 
	 * @return true if application's user class implements <tt>IUser.CookieAuthentication</tt>.  
	 */
	protected boolean cookieSignInSupported() {
		return IUser.CookieAuth.class.isAssignableFrom(((IAuthSettings)Application.get()).getUserClass());
	}

	/**
	 * @return true if signed in, false if credentials incorrect
	 */
	public boolean signIn(String username, String password) {
		return signIn(username, password, false);
	}
	
	/**
	 * @param setCookie if true, sets cookie to remember user
	 * @return true if signed in, false if credentials incorrect
	 */
	public boolean signIn(final String username, final String password, boolean setCookie) {
		signOut();
		IUser potential = getUser(username);
		if (potential != null && (potential).checkPassword(password))
			signIn(potential, setCookie);
		
		return userId != null;
	}

	/**
	 * Sign in a user whose credentials have been validated elsewhere. The user object must exist,
	 * and already have been saved, in the current request's Hibernate session.
	 * @param user validated and persisted user, must be in current Hibernate session
	 * @param setCookie if true, sets cookie to remember user
	 */
	public void signIn(IUser user, boolean setCookie) {
		userId = Databinder.getHibernateSession().getIdentifier(user);
		if (setCookie)
			setCookie();
	}
	
	/**
	 * Attempts cookie sign in, which will set usename field but not user.
	 * @return true if signed in, false if credentials incorrect or unavailable
	 */
	protected boolean cookieSignIn() {
		DataRequestCycle requestCycle = (DataRequestCycle) RequestCycle.get();
		Cookie userCookie = requestCycle.getCookie(getUserCookieName()),
			token = requestCycle.getCookie(getAuthCookieName());

		if (userCookie != null && token != null) {
			IUser potential;
			try {
				potential = getUser(URLDecoder.decode(userCookie.getValue(), CHARACTER_ENCODING));
			} catch (UnsupportedEncodingException e) {
				throw new WicketRuntimeException(e);
			}
			if (potential != null && potential instanceof IUser.CookieAuth) {
				IAuthSettings app = (IAuthSettings)getApplication();
				String correctToken = app.getToken((IUser.CookieAuth)potential);
				if (correctToken.equals(token.getValue()))
					signIn(potential, false);
			}
		}
		return userId != null;
	}
		
	/**
	 * Looks for a persisted IUser object matching the given username. Uses the user class
	 * and criteria builder returned from the application subclass implementing IAuthSettings.
	 * @param username
	 * @return user object from persistent storage
	 * @see IAuthSettings
	 */
	protected IUser getUser(final String username) {
		try {
			IAuthSettings app = (IAuthSettings)getApplication();
			Criteria criteria = Databinder.getHibernateSession().createCriteria(app.getUserClass());
			app.getUserCriteriaBuilder(username).build(criteria);
			return (IUser) criteria.uniqueResult();
		} catch (NonUniqueResultException e){
			throw new WicketRuntimeException("Multiple users returned for query", e); 
		}
	}

	/**
	 * @param userId Hibernate entity identifier
	 * @return user with given userId
	 */
	protected IUser getUser(final Serializable userId) {
		IAuthSettings app = (IAuthSettings)getApplication();
		return (IUser) Databinder.getHibernateSession().load(app.getUserClass(), userId);
	}
	
	public static String getUserCookieName() {
		return Application.get().getClass().getSimpleName() + "_USER";
	}
	
	public static String getAuthCookieName() {
		return Application.get().getClass().getSimpleName() + "_AUTH";
	}

	/**
	 * Sets cookie to remember the currently signed-in user.
	 */
	protected void setCookie() {
		if (userId == null)
			throw new WicketRuntimeException("User must be signed in when calling this method");
		if (!cookieSignInSupported())
			throw new UnsupportedOperationException("Must use an implementation of IUser.CookieAuth");
		
		IUser.CookieAuth cookieUser = (IUser.CookieAuth) getUser();
		WebResponse resp = (WebResponse) RequestCycle.get().getResponse();
		
		IAuthSettings app = (IAuthSettings)getApplication();
		
		int  maxAge = (int) getSignInCookieMaxAge().seconds();
		
		Cookie name, auth;
		try {
			name = new Cookie(getUserCookieName(), 
					URLEncoder.encode(cookieUser.getUsername(), CHARACTER_ENCODING));
			auth = new Cookie(getAuthCookieName(), app.getToken(cookieUser));
		} catch (UnsupportedEncodingException e) {
			throw new WicketRuntimeException(e);
		}
		
		name.setPath("/");
		auth.setPath("/");

		name.setMaxAge(maxAge);
		auth.setMaxAge(maxAge);
		
		resp.addCookie(name);
		resp.addCookie(auth);
	}
	
	/** Detach user from session */
	public void signOut() {
		userId = null;
		DataRequestCycle requestCycle = (DataRequestCycle) RequestCycle.get();
		requestCycle.clearCookie(getUserCookieName());
		requestCycle.clearCookie(getAuthCookieName());
	}
}
