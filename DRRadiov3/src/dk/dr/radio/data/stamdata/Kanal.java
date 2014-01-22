/**
 DR Radio 2 is developed by Jacob Nordfalk, Hanafi Mughrabi and Frederik Aagaard.
 Some parts of the code are loosely based on Sveriges Radio Play for Android.

 DR Radio 2 for Android is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License version 2 as published by
 the Free Software Foundation.

 DR Radio 2 for Android is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 DR Radio 2 for Android.  If not, see <http://www.gnu.org/licenses/>.

 */

package dk.dr.radio.data.stamdata;

import org.json.JSONObject;

import java.util.HashMap;

public class Kanal {

  public String kode;
  public String navn;
  public String shoutcastUrl;
  public String aacUrl;
  public String rtspUrl;


  public String slug;
  public JSONObject json;
  public HashMap<String, JSONObject> lydUrl = new HashMap<String, JSONObject>();

  /**
   * Eksemlelvis v3_kanalside__p3.json
   * http://www.dr.dk/tjenester/mu-apps/schedule/P3 .
   * Er sorteret kronologisk og skal vises sådan. Starter d.d. om morgenen.
   * Tilføj /1, /2, …  i URL for at se senere. /7 er max.
   * Se tidligere med /-1 , /-2 etc
   */
  public String kanalside;
}