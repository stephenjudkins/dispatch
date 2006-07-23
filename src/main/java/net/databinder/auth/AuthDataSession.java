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
package net.databinder.auth;

/**
 * Holds IUser instance for signed in users. Expects IUser implementation to be an annotated
 * class where with a unique username property.
 */
import javax.servlet.http.Cookie;

import net.databinder.DataRequestCycle;
import net.databinder.DataSession;
import net.databinder.auth.data.IUser;
import net.databinder.models.HibernateObjectModel;
import net.databinder.models.IQueryBinder;

import org.hibernate.Query;
import org.hibernate.QueryException;

import wicket.Application;
import wicket.RequestCycle;
import wicket.model.IModel;
import wicket.protocol.http.WebResponse;
import wicket.util.time.Duration;

public class AuthDataSession extends DataSession {
	private IModel user;
	private Class userClass;
	public static final String AUTH_COOKIE = "AUTH", USERNAME_COOKIE = "USER";
	
	/**
	 * Initialize new session. Retains user class from AuthDataApplication instance.
	 */
	protected AuthDataSession(AuthDataApplication application) {
		super(application);
		userClass = application.getUserClass();
	}
	
	/**
	 * @return IUser object for current user, or null if none signed in.
	 */
	public IUser getUser() {
		return isSignedIn() ? (IUser) user.getObject(null) : null;
	}
	
	/**
	 * @return length of time sign-in cookie should persist, defined here as one month
	 */
	protected Duration getSignInCookieMaxAge() {
		return Duration.days(31);
	}
	
	/**
	 * @return true if signed in or cookie sign in is possible and successful
	 */
	public boolean isSignedIn() {
		return user != null || (cookieSignInSupported() && cookieSignIn());
	}
	
	/** 
	 * @return true if application's user class implements <tt>IUser.CookieAuthentication</tt>.  
	 */
	protected boolean cookieSignInSupported() {
		return IUser.CookieAuth.class.isAssignableFrom(((AuthDataApplication)Application.get()).getUserClass());
	}

	/**
	 * @return true if signed in, false if credentials incorrect
	 */
	public boolean signIn(final String username, final String password, boolean setCookie) {
		if (!signIn(username, password))
			return false;

		if (setCookie && cookieSignInSupported()) {
			IUser.CookieAuth cookieUser = (IUser.CookieAuth) user.getObject(null);
			WebResponse resp = (WebResponse) RequestCycle.get().getResponse();
			
			int  maxAge = (int) getSignInCookieMaxAge().seconds();
			
			Cookie name = new Cookie(USERNAME_COOKIE, username),
				auth = new Cookie(AUTH_COOKIE, cookieUser.getToken());
			
			name.setMaxAge(maxAge);
			auth.setMaxAge(maxAge);
			
			resp.addCookie(name);
			resp.addCookie(auth);
		}
		return true;
	}
	
	/**
	 * @param username
	 * @return user object from persistent storage
	 */
	protected IModel getUser(final String username) {
		String query = "from " + userClass.getCanonicalName() 
		+ " where username = :username";
	
		try {
			IModel user = new HibernateObjectModel(query, new IQueryBinder() {
				public void bind(Query query) {
					query.setString("username", username);
				}
			});
			return user;
		} catch (QueryException e){
			return null;
		}
	}
	
	/**
	 * @return true if signed in, false if credentials incorrect
	 */
	public boolean signIn(String username, String password) {
		IModel potential = getUser(username);
		if (potential != null && ((IUser)potential.getObject(null)).checkPassword(password))
			user =  potential;
		
		return user != null;
	}
	
	/**
	 * @return true if signed in, false if credentials incorrect or unavailable
	 */
	protected boolean cookieSignIn() {
		DataRequestCycle requestCycle = (DataRequestCycle) RequestCycle.get();
		Cookie username = requestCycle.getCookie(USERNAME_COOKIE),
			token = requestCycle.getCookie(AUTH_COOKIE);

		if (username != null && token != null) {
			IModel potential = getUser(username.getValue());
			if (potential != null && potential.getObject(null) instanceof IUser.CookieAuth) {
				String correctToken = ((IUser.CookieAuth)potential.getObject(null)).getToken();
				if (correctToken.equals(token.getValue()))
					user =  potential;
			}
		}
		return user != null;
	}
	
	/** Detach user from session */
	public void signOut() {
		user = null;
		DataRequestCycle requestCycle = (DataRequestCycle) RequestCycle.get();
		requestCycle.clearCookie(AUTH_COOKIE);
		requestCycle.clearCookie(USERNAME_COOKIE);
	}
	
	/**
	 * Deatch our user model, which would not get the message otherwise.
	 */
	@Override
	protected void detach() {
		if (user != null) user.detach();
		super.detach();
	}
}
