/*
 * This file is part of the Goobi Application - a Workflow tool for the support of
 * mass digitization.
 *
 * Visit the websites for more information.
 *     - http://gdz.sub.uni-goettingen.de
 *     - http://www.goobi.org
 *     - http://launchpad.net/goobi-production
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details. You
 * should have received a copy of the GNU General Public License along with this
 * program; if not, write to the Free Software Foundation, Inc., 59 Temple Place,
 * Suite 330, Boston, MA 02111-1307 USA
 */

package org.goobi.api.display.enums;
public enum DisplayType {

	
	input("0","input"),select("1","select"),select1("2","select1"),textarea("3","textarea"); 
	
	private String id;
	private String title;

	private DisplayType(String myId, String myTitle) {
		id = myId;
		title = myTitle;
	}

	public String getId() {
		return id;
	}
	
	public String getTitle() {
		return title;
	}
	
	public static DisplayType getByTitle(String inTitle){
		if (inTitle != null) {
			for (DisplayType type : DisplayType.values()) {
				if (type.getTitle().equals(inTitle)) {
					return type;
				}
			}
		} 
		return textarea; // textarea is default
	}
}