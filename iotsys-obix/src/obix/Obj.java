/*
 * This code licensed to public domain
 */
package obix;

import java.lang.reflect.Array;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;

import obix.contracts.AckAlarm;
import obix.contracts.Alarm;
import obix.io.BinObix;
import obix.io.ObixEncoder;
import at.ac.tuwien.auto.iotsys.obix.observer.ExternalObserver;
import at.ac.tuwien.auto.iotsys.obix.observer.Observer;
import at.ac.tuwien.auto.iotsys.obix.observer.Subject;

/**
 * Obj is the base class for representing Obix objects and managing their tree structure.
 * 
 * @author Brian Frank
 * @creation 27 Apr 05
 * @version $Revision$ $Date$
 */

public class Obj implements IObj, Subject, AlarmSource, Cloneable
{
	// //////////////////////////////////////////////////////////////
	// Translations
	// //////////////////////////////////////////////////////////////

	public enum TranslationAttribute {
		display, displayName, location,
	}

	public static final String DEFAULT_LANGUAGE = "en";

	private HashMap<TranslationAttribute, HashMap<String, String>> translations = new HashMap<TranslationAttribute, HashMap<String, String>>();

	public void addTranslation(String language, String attribute, String value) throws Exception
	{
		attribute = attribute.toLowerCase().trim();

		if (attribute.equals("name"))
		{
			addTranslation(language, TranslationAttribute.displayName, value);
		}
		else if (attribute.equals("description"))
		{
			addTranslation(language, TranslationAttribute.display, value);
		}
		// location Translation
		else if (attribute.equals("location"))
		{
			addTranslation(language, TranslationAttribute.location, value);
		}
		else
		{
			throw new Exception("translation attribute '" + attribute + "' is not supported");
		}
	}

	public void addTranslations(Obj obj)
	{
		this.translations = obj.translations;
	}

	public void addTranslation(String language, TranslationAttribute attribute, String value)
	{
		if (value != null)
		{
			// alter language
			language = language.toLowerCase().trim();

			if (language.indexOf("-") > 0)
			{
				language = language.substring(0, language.indexOf("-"));
			}

			// get map
			if (!translations.containsKey(attribute))
				translations.put(attribute, new HashMap<String, String>());

			HashMap<String, String> attributeTranslations = translations.get(attribute);

			// add to map
			if (!attributeTranslations.containsKey(language))
			{
				attributeTranslations.put(language, value);
			}
		}
	}

	public String getTranslation(String language, TranslationAttribute attribute)
	{
		// check availability of translation
		if (language == null || !translations.containsKey(attribute) || (!translations.get(attribute).containsKey(language) && !translations.get(attribute).containsKey(DEFAULT_LANGUAGE)))
		{
			switch (attribute)
			{
				case display:
					return this.getDisplay();
				case displayName:
					return this.getDisplayName();
				// add translation for location
				case location:
					return this.getLocation();
			}
		}

		// get map of translations
		HashMap<String, String> attributeTranslations = translations.get(attribute);

		// return translation in default language
		if (!attributeTranslations.containsKey(language))
		{
			return attributeTranslations.get(DEFAULT_LANGUAGE);
		}

		// return language specific translation
		return attributeTranslations.get(language);
	}

	// //////////////////////////////////////////////////////////////
	// Fields
	// //////////////////////////////////////////////////////////////

	private String name;
	private Uri href;
	private Contract is;
	private Obj parent;
	private HashMap<String, Obj> kidsByName = new HashMap<String, Obj>();

	private Obj kidsHead, kidsTail;
	private int kidsCount;
	private Obj prev, next;
	private String display;
	private String displayName;
	private Uri icon;
	private boolean writable;
	private boolean readable;
	private boolean isNull;
	private boolean isHidden;
	private String location;
	

	private boolean isDisabled = false;
	private boolean isFaulty = false;
	private boolean isDown = false;
	private boolean isOverridden = false;

	private String invokedHref = new String();

	private LinkedList<Alarm> alarms = new LinkedList<Alarm>();
	private LinkedList<Alarm> unackedAlarms = new LinkedList<Alarm>();

	private long lastRefresh;
	private long refreshInterval;
	
	private Uri normalizedHref = null;

	// //////////////////////////////////////////////////////////////
	// Factory
	// //////////////////////////////////////////////////////////////

	/**
	 * Get an Obj Class for specified element name or return null if not a oBIX element name.
	 */
	public static Class<?> toClass(String elemName)
	{
		return (Class<?>) elemNameToClass.get(elemName);
	}

	/**
	 * Get an Obj class for the specified binary object code or return null if an invalid code.
	 */
	public static Class<?> toClass(int binCode)
	{
		return (Class<?>) binCodeToClass.get(new Integer(binCode));
	}

	private static ExternalObserver extObserver = null;

	/**
	 * Convenience for <code>toClass(elemName).newInstance()</code>. This method will return null if elemName is not a oBIX element name.
	 */
	public static Obj toObj(String elemName)
	{
		Class<?> cls = toClass(elemName);
		if (cls == null)
			return null;
		try
		{
			return (Obj) cls.newInstance();
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e.toString());
		}
	}

	static HashMap<String, Class<?>> elemNameToClass = new HashMap<String, Class<?>>();
	static HashMap<Integer, Class<?>> binCodeToClass = new HashMap<Integer, Class<?>>();
	static
	{
		mapClass("obj", BinObix.OBJ, Obj.class);
		mapClass("str", BinObix.STR, Str.class);
		mapClass("bool", BinObix.BOOL, Bool.class);
		mapClass("int", BinObix.INT, Int.class);
		mapClass("enum", BinObix.ENUM, Enum.class);
		mapClass("list", BinObix.LIST, List.class);
		mapClass("real", BinObix.REAL, Real.class);
		mapClass("uri", BinObix.URI, Uri.class);
		mapClass("abstime", BinObix.ABSTIME, Abstime.class);
		mapClass("reltime", BinObix.RELTIME, Reltime.class);
		mapClass("time", BinObix.TIME, Time.class);
		mapClass("date", BinObix.DATE, Date.class);
		mapClass("op", BinObix.OP, Op.class);
		mapClass("ref", BinObix.REF, Ref.class);
		mapClass("err", BinObix.ERR, Err.class);
		mapClass("feed", BinObix.FEED, Feed.class);
	}

	private static void mapClass(String elemName, int binCode, Class<?> cls)
	{
		elemNameToClass.put(elemName, cls);
		binCodeToClass.put(new Integer(binCode), cls);
	}

	// //////////////////////////////////////////////////////////////
	// Constructor
	// //////////////////////////////////////////////////////////////

	/**
	 * Construct a named Obj.
	 */
	public Obj(String name)
	{
		this.name = name;
	}

	/**
	 * Construct an unnamed Obj.
	 */
	public Obj()
	{
		this(null);
	}

	// //////////////////////////////////////////////////////////////
	// Identity
	// //////////////////////////////////////////////////////////////

	/**
	 * Get element type name of this object.
	 */
	public String getElement()
	{
		return "obj";
	}

	/**
	 * Get binary code for this object type.
	 */
	public int getBinCode()
	{
		return BinObix.OBJ;
	}

	/**
	 * Returns a reference to the Obj
	 */
	public Ref getReference()
	{
		return getReference(false);
	}

	public Ref getReference(boolean absolute)
	{
		Ref ref = new Ref();
		ref.setName(this.getName());
		ref.setIs(this.getIs());
		ref.setDisplayName(this.getDisplayName());
		ref.addTranslations(this);

		if (absolute)
		{
			Obj tmp = this;
			String uri = "";

			do
			{
				if (uri.isEmpty())
					uri = tmp.getHref().getPath();
				else
					uri = tmp.getHref().getPath() + "/" + uri;
				tmp = tmp.parent;
			}
			while (tmp != null && !tmp.getHref().isAbsolute());

			ref.setHref(new Uri(uri));
		}
		else
		{
			ref.setHref(this.getHref());
		}
		return ref;
	}

	/**
	 * Get name of this Obj or null if unnamed.
	 */
	public String getName()
	{
		return name;
	}

	/**
	 * Set name of this Obj. The name may only be set if name is currently null and this Obj hasn't been added a child to another Obj yet.
	 */
	public void setName(String name)
	{
		if (this.name != null)
			throw new IllegalStateException("name is already set");
		if (this.parent != null)
			throw new IllegalStateException("obj is already parented");
		this.name = name;
	}

	/**
	 * Set name of this Obj. The name may only be set if name is currently null and this Obj hasn't been added a child to another Obj yet.
	 * 
	 * @param name
	 *            New name for this Obj
	 * @param force
	 *            If true, ignore that name or parent may have already been set
	 */
	public void setName(String name, boolean force)
	{
		if (force)
			this.name = name;
		else
			setName(name);
	}

	/**
	 * Return parent object or null if unparented.
	 */
	public Obj getParent()
	{
		return parent;
	}

	/**
	 * Get the root parent of this object.
	 */
	public Obj getRoot()
	{
		if (parent == null)
			return this;
		else
			return parent.getRoot();
	}

	/**
	 * Get uri of this object is well known otherwise return null.
	 */
	public Uri getHref()
	{
		return href;
	}

	/**
	 * Get the absolute normalized href of this object, based on the href of this obj's root object. Return null if this object doesn't have an href.
	 */
	public Uri getNormalizedHref()
	{
		if (href == null)
			return null;

		if(normalizedHref != null){
			return normalizedHref;
		}
		
		if (getParent() != null && getParent().getNormalizedHref() != null && getParent().getNormalizedHref().isAbsolute())
		{
			normalizedHref = href.normalize(getParent().getNormalizedHref());
			return normalizedHref;
		}

		return href;
	}

	public Uri getRelativePath()
	{
		if (href != null && href.getPath() != null)
		{
			int lastIndexOf = href.getPath().lastIndexOf("/");
			if (lastIndexOf > -1)
			{
				new Uri(href.getPath().substring(lastIndexOf));
			}
		}
		return href;
	}

	public String getFullContextPath()
	{
		String path = "";

		Obj current = this;
		while (current != null)
		{
			Uri normalizedHref = current.getNormalizedHref();
			if (normalizedHref != null)
			{
				if (normalizedHref.isAbsolute() || normalizedHref.getPath().startsWith("/"))
				{
					String fullContextPath = normalizedHref.getPath() + path;
					return fullContextPath;
				}
				else
				{
					if (!path.isEmpty() && !path.startsWith("/"))
						path = "/" + path;
					path = current.getNormalizedHref().getPath().toString() + path;
				}
			}
			else if (current.getHref() != null)
			{
				path = current.getHref().toString() + path;
			}
			else
			{
				path = path + "/";
			}

			current = current.getParent();
		}

		return path;
	}

	/**
	 * Set uri of this object.
	 */
	public void setHref(Uri href)
	{
		this.href = href;
	}

	/**
	 * Convenience for <code>is(new Uri(uri))</code>.
	 */
	public boolean is(String uri)
	{
		return is(new Uri(uri));
	}

	/**
	 * Return if the contract list defined by the is attribute contains the specified URI.
	 */
	public boolean is(Uri uri)
	{
		if (is == null)
			return false;
		return is.contains(uri);
	}

	/**
	 * Get the Contracts list this obj supports.
	 */
	public Contract getIs()
	{
		return is;
	}

	/**
	 * Set the contracts list this obj supports.
	 */
	public void setIs(Contract is)
	{
		this.is = is;
	}

	public Object clone() throws CloneNotSupportedException
	{

		Obj obj = (Obj) super.clone();
		obj.name = null;
		obj.parent = null;
		return obj;
	}

	// //////////////////////////////////////////////////////////////
	// Convenience
	// //////////////////////////////////////////////////////////////

	/** Return if this is an instance of Val */
	@Override
	public boolean isVal()
	{
		return this instanceof Val;
	}

	/** Return if this is an instance of Bool */
	@Override
	public boolean isBool()
	{
		return this instanceof Bool;
	}

	/** Return if this is an instance of Int */
	@Override
	public boolean isInt()
	{
		return this instanceof Int;
	}

	/** Return if this is an instance of Real */
	@Override
	public boolean isReal()
	{
		return this instanceof Real;
	}

	/** Return if this is an instance of Str */
	@Override
	public boolean isStr()
	{
		return this instanceof Str;
	}

	/** Return if this is an instance of Enum */
	@Override
	public boolean isEnum()
	{
		return this instanceof Enum;
	}

	/** Return if this is an instance of Uri */
	@Override
	public boolean isUri()
	{
		return this instanceof Uri;
	}

	/** Return if this is an instance of Abstime */
	@Override
	public boolean isAbstime()
	{
		return this instanceof Abstime;
	}

	/** Return if this is an instance of Reltime */
	@Override
	public boolean isReltime()
	{
		return this instanceof Reltime;
	}

	/** Return if this is an instance of Date */
	@Override
	public boolean isDate()
	{
		return this instanceof Date;
	}

	/** Return if this is an instance of Time */
	@Override
	public boolean isTime()
	{
		return this instanceof Time;
	}

	/** Return if this is an instance of List */
	@Override
	public boolean isList()
	{
		return this instanceof List;
	}

	/** Return if this is an instance of Op */
	@Override
	public boolean isOp()
	{
		return this instanceof Op;
	}

	/** Return if this is an instance of Feed */
	@Override
	public boolean isFeed()
	{
		return this instanceof Feed;
	}

	/** Return if this is an instance of Ref */
	@Override
	public boolean isRef()
	{
		return this instanceof Ref;
	}

	/** Return if this is an instance of Err */
	@Override
	public boolean isErr()
	{
		return this instanceof Err;
	}

	/** Convenience for getting the value as if a Bool */
	@Override
	public boolean getBool()
	{
		return ((Bool) this).get();
	}

	/** Convenience for setting the value as if a Bool */
	@Override
	public void setBool(boolean val)
	{
		((Bool) this).set(val);
	}

	/** Convenience for getting the value as if an Int */
	@Override
	public long getInt()
	{
		return ((Int) this).get();
	}

	/** Convenience for setting the value as if an Int */
	@Override
	public void setInt(long val)
	{
		((Int) this).set(val);
	}

	/** Convenience for getting the value as if a Real */
	@Override
	public double getReal()
	{
		return ((Real) this).get();
	}

	/** Convenience for setting the value as if a Real */
	@Override
	public void setReal(double val)
	{
		((Real) this).set(val);
	}

	/** Convenience for getting the value as if a Str */
	@Override
	public String getStr()
	{
		return ((Str) this).get();
	}

	/** Convenience for setting the value as if a Str */
	@Override
	public void setStr(String val)
	{
		((Str) this).set(val);
	}

	/** Convenience for setting the value to the value of another Obj */
	@Override
	public void set(IObj obj)
	{
		throw new RuntimeException("Not implemented yet.");
	}

	// //////////////////////////////////////////////////////////////
	// Facets
	// //////////////////////////////////////////////////////////////

	/**
	 * Get the display string for this obj. If the display facet is specified return it, otherwise return type information.
	 */
	public String toDisplayString()
	{
		if (display != null)
			return display;
		if (this instanceof Val)
			return ((Val) this).encodeVal();
		if (is != null && is.size() > 0)
			return is.toString();
		return "obix:" + getElement();
	}

	/**
	 * Get display String or null if not specified.
	 */
	public String getDisplay()
	{
		return display;
	}

	/**
	 * Set display string or null if not specified.
	 */
	public void setDisplay(String display)
	{
		if (display != null && display.isEmpty())
			display = null;

		this.display = display;
	}

	/**
	 * If displayName is specified return it, otherwise return name.
	 */
	public String toDisplayName()
	{
		if (displayName != null)
			return displayName;
		if (name != null)
			return name;
		return getElement();
	}

	/**
	 * Get displayName String or null if not specified.
	 */
	public String getDisplayName()
	{
		return displayName;
	}

	/**
	 * Set displayName string or null if not specified.
	 */
	public void setDisplayName(String displayName)
	{
		if (displayName != null && displayName.isEmpty())
			displayName = null;

		this.displayName = displayName;
	}

	/**
	 * Get icon URI or null if not specified.
	 */
	public Uri getIcon()
	{
		return icon;
	}

	/**
	 * Set icon URI or null if not specified.
	 */
	public void setIcon(Uri icon)
	{
		this.icon = icon;
	}

	/**
	 * Get status for this object.
	 */
	public Status getStatus()
	{
		if (isDisabled())
			return Status.disabled;

		if (isFaulty())
			return Status.fault;

		if (isDown())
			return Status.down;

		if (inAlarmState())
		{
			ArrayList<Alarm> unackedActiveAlarms = new ArrayList<Alarm>(alarms);
			unackedActiveAlarms.retainAll(unackedAlarms);

			if (!unackedActiveAlarms.isEmpty())
				return Status.unackedAlarm;
			else
				return Status.alarm;
		}

		if (!unackedAlarms.isEmpty())
			return Status.unacked;

		if (isOverridden())
			return Status.overridden;

		return Status.ok;
	}

	/**
	 * Set status for this object. If null is passed, then status is to Status.ok.
	 */
	public void setStatus(Status status)
	{
		if (status == null)
			status = Status.ok;

		setDisabled(status == Status.disabled);
		setFaulty(status == Status.fault);
		setDown(status == Status.down);
		setOverridden(status == Status.overridden);
	}

	public boolean isDisabled()
	{
		return isDisabled;
	}

	public void setDisabled(boolean isDisabled)
	{
		this.isDisabled = isDisabled;
	}

	public boolean isFaulty()
	{
		return isFaulty;
	}

	public void setFaulty(boolean isFaulty)
	{
		this.isFaulty = isFaulty;
	}

	public boolean isDown()
	{
		return isDown;
	}

	public void setDown(boolean isDown)
	{
		this.isDown = isDown;
	}

	public boolean isOverridden()
	{
		return isOverridden;
	}

	public void setOverridden(boolean isOverridden)
	{
		this.isOverridden = isOverridden;
	}

	/**
	 * Get null flag or default to false.
	 */
	public boolean isNull()
	{
		return isNull;
	}

	/**
	 * Set null flag.
	 */
	public void setNull(boolean isNull)
	{
		this.isNull = isNull;
	}

	/**
	 * Get hidden flag or default to false. Hidden objects are not printed when printing an ancestor
	 */
	public boolean isHidden()
	{
		return isHidden;
	}

	/**
	 * Set hidden flag. Hidden objects are not printed when printing an ancestor
	 */
	public void setHidden(boolean isHidden)
	{
		this.isHidden = isHidden;
	}

	/**
	 * Get writable flag or default to false.
	 */
	public boolean isWritable()
	{
		return writable;
	}

	/**
	 * Convenience for <code>setWritable(writable, false)</code>.
	 */
	public void setWritable(boolean writable)
	{
		setWritable(writable, false);
	}

	/**
	 * Set writable flag. If recursive is true, then recursively call setWritable on all this object's children.
	 */
	public void setWritable(boolean writable, boolean recursive)
	{
		this.writable = writable;
		if (recursive)
		{
			Obj[] kids = list();
			for (int i = 0; i < kids.length; ++i)
				kids[i].setWritable(writable, recursive);
		}
	}

	/**
	 * Get readable flag or default to false.
	 */
	public boolean isReadable()
	{
		return readable;
	}

	/**
	 * Set readable flag.
	 */
	public void setReadable(boolean readable)
	{
		this.readable = readable;
	}
	
	/**
	 * Set location string
	 * @param location
	 */
	public void setLocation(String location)
	{
		this.location = location;
	}
	
	/**
	 * Get location of obj
	 * @return location
	 */
	public String getLocation()
	{
		return location;
	}

	// //////////////////////////////////////////////////////////////
	// Children
	// //////////////////////////////////////////////////////////////

	/**
	 * Return if this object has a sub object by the specified name.
	 */
	public boolean has(String name)
	{
		if (kidsByName == null)
			return false;
		return kidsByName.get(name) != null;
	}

	/**
	 * Get a sub object by name or return null.
	 */
	public Obj get(String name)
	{
		if (kidsByName == null)
			return null;
		return (Obj) kidsByName.get(name);
	}

	/**
	 * Lookup a sub object by name and return its href. If the child doesn't exist or has a null href, then throw an exception.
	 */
	public Uri getChildHref(String name)
	{
		Obj kid = get(name);
		if (kid == null)
			throw new IllegalStateException("Missing child object: " + name);
		if (kid.getHref() == null)
			throw new IllegalStateException("Child missing href : " + name);
		return kid.getNormalizedHref();
	}

	/**
	 * Get a child object with the specified href
	 */
	public Obj getChildByHref(Uri href)
	{
		if (href == null)
			return null;

		String childHref = href.get();

		while (childHref.endsWith("/"))
			childHref = childHref.substring(0, childHref.length() - 1);
		while (childHref.contains("//"))
			childHref = childHref.replaceAll("//", "/");

		synchronized (this)
		{
			for (Obj p = kidsHead; p != null; p = p.next)
			{
				if (p.getHref() != null && !p.isRef())
				{
					String pHref = p.getHref().get();
					while (pHref.endsWith("/"))
						pHref = pHref.substring(0, pHref.length() - 1);
					while (pHref.contains("//"))
						pHref = pHref.replaceAll("//", "/");

					if (pHref.equals(childHref))
						return p;
				}
			}
		}

		return null;
	}

	/**
	 * Get a sub object with the specified href
	 */
	public Obj getByHref(Uri href)
	{
		if (href == null)
			return null;
		if (getRoot() != this)
		{
			if (href.get().startsWith("/") || href.isAbsolute())
				return getRoot().getByHref(href);

			return getRoot().getByHref(new Uri(this.getFullContextPath() + "/" + href.get()));
		}

		String uri = href.get();
		while (uri.startsWith("/"))
			uri = uri.substring(1);

		uri = URI.create(uri).normalize().getPath();

		String unresolvedUri = uri;
		Obj current = this;

		while (!unresolvedUri.isEmpty())
		{
			Obj child = current.getChildByHref(new Uri(uri));
			if (child == null)
				child = current.getChildByHref(new Uri(current.getFullContextPath() + "/" + uri));

			if (child == null)
			{
				int slash = uri.lastIndexOf('/');
				if (slash == -1)
					return null;
				uri = uri.substring(0, slash);
			}
			else
			{
				current = child;
				unresolvedUri = unresolvedUri.substring(uri.length());
				while (unresolvedUri.startsWith("/"))
					unresolvedUri = unresolvedUri.substring(1);
				uri = unresolvedUri;
			}
		}

		return current;
	}

	/**
	 * Return number of child objects.
	 */
	public int size()
	{
		return kidsCount;
	}

	/**
	 * Get an array of all the children.
	 */
	public synchronized Obj[] list()
	{
		Obj[] list = new Obj[kidsCount];
		int n = 0;
		for (Obj p = kidsHead; p != null && n < kidsCount; p = p.next)
			list[n++] = p;
		return list;
	}

	/**
	 * Get all the children which are instances of the specified class. The return array will be of the specified class.
	 */
	public synchronized Object[] list(Class<?> cls)
	{
		Object[] temp = new Object[kidsCount];
		int count = 0;
		for (Obj p = kidsHead; p != null; p = p.next)
		{
			if (cls.isInstance(p))
				temp[count++] = p;
		}

		Object[] result = (Object[]) Array.newInstance(cls, count);
		System.arraycopy(temp, 0, result, 0, count);
		return result;
	}

	/**
	 * Convenience for <code>kid.setName(name); add(kid);</code>. The specified kid must be unnamed. Return this.
	 */
	public Obj add(String name, Obj kid)
	{
		kid.setName(name);
		return add(kid);
	}

	/**
	 * Add a child Obj. Return this.
	 */
	public synchronized Obj add(Obj kid, boolean checkDuplicates)
	{
		// sanity check
		// if (kid.parent != null || kid.prev != null || kid.next != null)
		// throw new IllegalStateException("Child is already parented");

		// check only, if the current and the stored kid are not hidden
		// now it is possible to store a visible reference and the hidden obj
		if (checkDuplicates && !isHidden())
		{
			if (kid.name != null && kidsByName != null && kidsByName.containsKey(kid.name) && !((Obj) kidsByName.get(kid.name)).isHidden())
				throw new IllegalStateException("Duplicate child name '" + kid.name + "'");
		}

		// if named and key is not contained, add to name map
		if (kid.name != null && !kidsByName.containsKey(kid.name))
		{
			kidsByName.put(kid.name, kid);
		}

		// add to ordered linked list
		if (kidsTail == null)
		{
			kidsHead = kidsTail = kid;
		}
		else
		{
			kidsTail.next = kid;
			kid.prev = kidsTail;
			kidsTail = kid;
		}

		// update kid's references and count
		if (kid.parent == null)
		{
			kid.parent = this;
		}
		kidsCount++;
		return this;
	}

	/**
	 * Add a child Obj. Return this.
	 */
	public Obj add(Obj kid)
	{
		return add(kid, false);
	}

	/**
	 * Add all the specified objects as my children. Return this.
	 */
	public synchronized Obj addAll(Obj[] kids)
	{
		for (int i = 0; i < kids.length; ++i)
			add(kids[i]);
		return this;
	}

	/**
	 * Remove the specified child Obj.
	 */
	public synchronized void remove(Obj kid)
	{
		// sanity checks
		if (kid.parent != this)
			throw new IllegalStateException("Not parented by me");

		// remove from name map if applicable
		if (kid.name != null)
			kidsByName.remove(kid.name);

		// remove from linked list
		if (kidsHead == kid)
		{
			kidsHead = kid.next;
		}
		else
		{
			kid.prev.next = kid.next;
		}
		if (kidsTail == kid)
		{
			kidsTail = kid.prev;
		}
		else
		{
			kid.next.prev = kid.prev;
		}

		// clear kid's references and count
		kid.parent = null;
		kid.prev = null;
		kid.next = null;
		kidsCount--;
	}

	/**
	 * Replace the old obj with the newObj (they must have the same name).
	 */
	public synchronized void replace(Obj oldObj, Obj newObj)
	{

		if (!oldObj.name.equals(newObj.name))
			throw new IllegalStateException("Mismatched names: " + oldObj.name + " != " + newObj.name);

		// sanity checks
		if (oldObj.parent != this)
			throw new IllegalStateException("oldObj not parented by me");
		if (newObj.parent != null)
			throw new IllegalStateException("newObj already parented");

		// replace in map
		kidsByName.put(newObj.name, newObj);

		// replace in linked list
		newObj.parent = this;
		newObj.prev = oldObj.prev;
		if (newObj.prev != null)
			newObj.prev.next = newObj;
		newObj.next = oldObj.next;
		if (newObj.next != null)
			newObj.next.prev = newObj;
		if (kidsHead == oldObj)
			kidsHead = newObj;
		if (kidsTail == oldObj)
			kidsTail = newObj;

		// clear oldObj
		oldObj.parent = null;
		oldObj.prev = null;
		oldObj.next = null;
	}

	/**
	 * Convenience for <code>getParent().remove(this)</code>.
	 */
	public void removeThis()
	{
		if (parent == null)
			throw new IllegalStateException("Not parented");

		parent.remove(this);
	}

	// //////////////////////////////////////////////////////////////
	// Debug
	// //////////////////////////////////////////////////////////////

	/**
	 * Get a debug string.
	 */
	public String toString()
	{
		StringBuffer s = new StringBuffer();
		s.append("<").append(getElement());
		if (name != null)
			s.append(" name=\"").append(name).append('"');
		if (href != null)
			s.append(" href=\"").append(href.get()).append('"');
		if (this instanceof Val)
			s.append(" val=\"").append(((Val) this).encodeVal()).append('"');
		s.append("/>");
		return s.toString();
	}

	/**
	 * Dump in XML to standard output.
	 */
	public void dump()
	{
		ObixEncoder.dump(this);
	}

	public String getInvokedHref()
	{
		return invokedHref;
	}

	public void setInvokedHref(String invokedHref)
	{
		this.invokedHref = invokedHref;
	}

	/**
	 * Write to on object should be handled by the object itself. Can be overridden by subclasses.
	 * 
	 * @param input
	 */
	public void writeObject(Obj input)
	{
	}

	/**
	 * If an object is read, then the concrete implementation should refresh the data.
	 */
	public void refreshObject()
	{
	}

	/**
	 * All observers of this object.
	 */
	private final HashSet<Observer> observers = new HashSet<Observer>();

	/**
	 * Attach an observer that will be notified if the state changes.
	 */
	@Override
	public void attach(Observer observer)
	{
		synchronized (observers)
		{
			observers.add(observer);
			observer.setSubject(this);
		}
	}

	/**
	 * Detach an observer.
	 */
	@Override
	public void detach(Observer observer)
	{
		synchronized (observers)
		{
			observers.remove(observer);
		}
	}

	/**
	 * Notify the observers about a state change and pass the current state. NOTE: It is possible that some updates are lost, if the object is updated while the notification is finished. This problem could only by solved by a major redesign of the update
	 * and notification concept of obix objects.
	 */

	@Override
	public void notifyObservers()
	{

		// first notify observers directly observing this object
		synchronized (observers)
		{
			Iterator<Observer> iterator = observers.iterator();

			while (iterator.hasNext())
			{
				((Observer) iterator.next()).update(getCurrentState());
			}
		}

		// notify all observers of parents, e.g. in the case of basic obix
		// objects (bool, int,...) it
		// is quite common that these objects are part of the extent of other
		// objects
		if (this.getParent() != null)
		{
			getParent().notifyObservers();
		}

		// notify CoAP observers, managed in ObixObservingManager
		if (extObserver != null)
		{
			extObserver.objectChanged(this.getFullContextPath());
		}
	}

	@Override
	public Object getCurrentState()
	{
		return this;
	}

	public static void setExternalObserver(ExternalObserver extObserver)
	{
		Obj.extObserver = extObserver;
	}

	/**
	 * Adds an alarm to this Obj's list of active alarms.
	 * 
	 * @param alarm
	 *            A alarm that entered its alarm condition
	 */
	public void setOffNormal(Alarm alarm)
	{
		alarms.add(alarm);

		if (alarm instanceof AckAlarm)
		{
			Abstime ackTimestamp = ((AckAlarm) alarm).ackTimestamp();
			if (ackTimestamp != null && ackTimestamp.isNull())
				unackedAlarms.add(alarm);
		}
	}

	/**
	 * Removes the specified alarm from this Obj's list of alarms
	 * 
	 * @param alarm
	 *            Alarm whose alarm condition has been exited
	 */
	public void setToNormal(Alarm alarm)
	{
		alarms.remove(alarm);
	}

	/**
	 * Removes the specified alarm from this Obj's list of unacknowledged alarms
	 * 
	 * @param alarm
	 *            Alarm that has been acknowledged
	 */
	public void alarmAcknowledged(Alarm alarm)
	{
		unackedAlarms.remove(alarm);
	}

	/**
	 * @return <code>true</code> if this Obj is currently in an alarm condition, otherwise <code>false</code>
	 */
	public boolean inAlarmState()
	{
		return !(alarms.isEmpty());
	}

	/**
	 * @return a list of alarms currently active for this Obj
	 */
	public LinkedList<Alarm> getAlarms()
	{
		if (alarms == null)
			alarms = new LinkedList<Alarm>();

		return alarms;
	}

	/**
	 * Method that can be overridden to contain logic that should be executed if all fields are set.
	 */
	public void initialize()
	{

	}

	/**
	 * Get the display string for this obj. If the display facet is specified return it, otherwise return type information.
	 */
	@Override
	public String toDisplay()
	{
		if (display != null)
			return display;
		if (this instanceof Val)
			return ((Val) this).encodeVal();
		if (is != null && is.size() > 0)
			return is.toString();
		return "obix:" + getElement();
	}

	// //////////////////////////////////////////////////////////////
	// Refreshable
	// //////////////////////////////////////////////////////////////

	/**
	 * Sets the object's refresh interval in milliseconds. Set the refresh interval to zero to disable refreshing.
	 */
	public void setRefreshInterval(long interval)
	{
		if (interval >= Refreshable.MIN_REFRESH_INTERVAL_MS || interval == 0)
			refreshInterval = interval;
		else
			refreshInterval = Refreshable.MIN_REFRESH_INTERVAL_MS;
	}

	/**
	 * Sets the millis of the object's last refresh.
	 */
	public void setLastRefresh(long millis)
	{
		lastRefresh = millis;
	}

	/**
	 * Get the millis of the object's last refresh.
	 */
	public long getLastRefresh()
	{
		return lastRefresh;
	}

	/**
	 * Get the object's refresh interval in milliseconds.
	 */
	public long getRefreshInterval()
	{
		return refreshInterval;
	}

	/**
	 * Checks whether or not the object needs to be refreshed.
	 */
	public boolean needsRefresh()
	{
		boolean needs = false;

		if (refreshInterval > 0)
			needs = System.currentTimeMillis() >= (lastRefresh + refreshInterval);

		return needs;
	}
	
	public ArrayList<Val> getValChilds(){
		Obj[] childs = this.list();
		ArrayList<Val> result = new ArrayList<Val>();
		
		if (this instanceof Val){
			System.out.println(this.getFullContextPath() + ": " + this.toString());
			result.add((Val)this);
			return result;
		}
		
		for (Obj o : childs){
			result.addAll(o.getValChilds());
		}
		
		return result;
	}

	public void getValChilds(ArrayList<Val> result){
		Obj[] childs = this.list();
		
		if (this instanceof Val){
			System.out.println(this.getFullContextPath() + ": " + this.toString());
			result.add((Val)this);
			return;
		}
		
		for (Obj o : childs){
			o.getValChilds(result);
		}
		
	}
}
